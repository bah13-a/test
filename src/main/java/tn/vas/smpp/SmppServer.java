package tn.vas.smpp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.*;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.session.*;
import org.jsmpp.util.DeliveryReceiptState;
import org.jsmpp.util.MessageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tn.vas.config.SmppServerProperties;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.event.DomainEvents;
import tn.vas.fraud.FraudService;
import tn.vas.repo.Repos.*;
import tn.vas.security.RateLimiter;
import tn.vas.service.*;
import tn.vas.support.Views;

/**
 * Serveur SMPP v3.4 pour clients externes (partenaires, agrégateurs) : un client API muni d'un accès SMPP se connecte (bind TX / RX / TRX)
 * avec son system_id et son mot de passe, depuis une adresse autorisée. Ses submit_sm suivent EXACTEMENT les règles de l'API REST :
 * expéditeur = short code d'un de ses services actifs, listes noire / blanche, antifraude, quota par minute, facturation au ledger, routage
 * par opérateur et débit SMPP vers Jasmin. Les accusés (registered_delivery) sont remis en deliver_sm, rejoués à la reconnexion s'il était
 * déconnecté ; les MO de ses services peuvent lui être remis (option smpp_mo). Messages longs : UDH (8 ou 16 bits) réassemblés, ou
 * message_payload. enquire_link géré par la bibliothèque.
 */
@Component
public class SmppServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SmppServer.class);
    private static final SecureRandom RNG = new SecureRandom();
    private final SmppServerProperties props;
    private final ApiClientRepo clients;
    private final ServiceRepo services;
    private final MtRepo mts;
    private final MoRepo mos;
    private final RuleRepo rules;
    private final MtService mtService;
    private final FraudService fraud;
    private final RateLimiter limiter;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "smpp-server"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "smpp-concat"); t.setDaemon(true); return t; });
    /** Connexions ouvertes par client API. */
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SMPPServerSession>> bound = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SMPPServerSession, Long> owner = new ConcurrentHashMap<>();
    /** Parties de messages concaténés en attente : clé client|destination|référence. */
    private final ConcurrentHashMap<String, Concat> concat = new ConcurrentHashMap<>();
    private volatile SMPPServerSessionListener listener;
    private volatile boolean running;

    private static final class Concat {
        final String[] parts;
        final String[] ids;
        final long started = System.currentTimeMillis();
        Concat(int n) { parts = new String[n]; ids = new String[n]; }
        synchronized boolean complete() { return Arrays.stream(parts).allMatch(Objects::nonNull); }
    }

    public SmppServer(SmppServerProperties props, ApiClientRepo clients, ServiceRepo services, MtRepo mts, MoRepo mos, RuleRepo rules, MtService mtService,
                      FraudService fraud, RateLimiter limiter, PasswordEncoder encoder, AuditService audit, PlatformTransactionManager txm) {
        this.props = props;
        this.clients = clients;
        this.services = services;
        this.mts = mts;
        this.mos = mos;
        this.rules = rules;
        this.mtService = mtService;
        this.fraud = fraud;
        this.limiter = limiter;
        this.encoder = encoder;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    // ------------------------------------------------------------------ cycle de vie

    @Override
    public void start() {
        if (!props.enabled()) return;
        try {
            listener = new SMPPServerSessionListener(props.port());
        } catch (IOException e) {
            throw new IllegalStateException("serveur SMPP : port " + props.port() + " indisponible", e);
        }
        running = true;
        pool.submit(() -> {
            while (running) {
                try {
                    SMPPServerSession s = listener.accept();
                    pool.submit(() -> bind(s));
                } catch (IOException e) {
                    if (running) log.warn("serveur SMPP : accept {}", e.getMessage());
                }
            }
        });
        janitor.scheduleAtFixedRate(this::expireConcat, 30, 30, TimeUnit.SECONDS);
        log.info("serveur SMPP v3.4 en écoute sur le port {}", props.port());
    }

    @Override
    public void stop() {
        running = false;
        owner.keySet().forEach(SMPPServerSession::unbindAndClose);
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        janitor.shutdownNow();
    }

    @Override public boolean isRunning() { return running; }

    /** Ferme toutes les connexions d'un client (accès retiré ou client révoqué). */
    public void disconnect(Long clientId) {
        var l = bound.remove(clientId);
        if (l != null) l.forEach(SMPPServerSession::unbindAndClose);
    }

    public int boundSessions(Long clientId) {
        var l = bound.get(clientId);
        return l == null ? 0 : l.size();
    }

    // ------------------------------------------------------------------ bind

    private void bind(SMPPServerSession s) {
        String ip = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
        try {
            BindRequest br = s.waitForBind(10_000);
            if (limiter.peek("smppbind:" + ip) >= props.bindFailuresPerMinute()) { br.reject(SMPPConstant.STAT_ESME_RBINDFAIL); s.close(); return; }
            ApiClient c = clients.findBySmppSystemIdAndActiveTrue(br.getSystemId()).orElse(null);
            int reject = c == null ? SMPPConstant.STAT_ESME_RINVSYSID
                    : c.getSmppPasswordHash() == null || br.getPassword() == null || !encoder.matches(br.getPassword(), c.getSmppPasswordHash()) ? SMPPConstant.STAT_ESME_RINVPASWD
                    : !ipAllowed(c, ip) ? SMPPConstant.STAT_ESME_RBINDFAIL
                    : br.getBindType() != BindType.BIND_RX && !scope(c, "messages:send") ? SMPPConstant.STAT_ESME_RBINDFAIL
                    : boundSessions(c.getId()) >= Math.max(1, c.getSmppMaxBinds()) ? SMPPConstant.STAT_ESME_RALYBND : 0;
            if (reject != 0) {
                limiter.hit("smppbind:" + ip);
                audit.log("SMPP_BIND_REFUSED", "smpp:" + br.getSystemId(), ip + " code " + reject);
                br.reject(reject);
                s.close();
                return;
            }
            br.accept("VASTN", InterfaceVersion.IF_34);
            s.setMessageReceiverListener(new Receiver(c.getId()));
            bound.computeIfAbsent(c.getId(), k -> new CopyOnWriteArrayList<>()).add(s);
            owner.put(s, c.getId());
            s.addSessionStateListener((n, o, src) -> {
                if (!n.isBound()) {
                    owner.remove(s);
                    var l = bound.get(c.getId());
                    if (l != null) l.remove(s);
                }
            });
            audit.log("SMPP_BIND", "smpp:" + c.getSmppSystemId(), ip + " " + br.getBindType());
            if (br.getBindType() != BindType.BIND_TX) pool.submit(() -> replayDlr(c.getId()));
        } catch (Exception e) {
            log.warn("serveur SMPP : bind {} échoué : {}", ip, e.getMessage());
            s.close();
        }
    }

    static boolean ipAllowed(ApiClient c, String ip) {
        String allowed = c.getSmppAllowedIps();
        if (allowed == null || allowed.isBlank()) return true;
        return Arrays.stream(allowed.split(",")).map(String::strip).anyMatch(a -> a.equals(ip));
    }

    private static boolean scope(ApiClient c, String s) {
        return c.getScopes() != null && Arrays.stream(c.getScopes().split(",")).map(String::strip).anyMatch(s::equals);
    }

    // ------------------------------------------------------------------ submit_sm

    private final class Receiver implements ServerMessageReceiverListener {
        private final long clientId;

        Receiver(long clientId) { this.clientId = clientId; }

        @Override
        public SubmitSmResult onAcceptSubmitSm(SubmitSm sm, SMPPServerSession session) throws ProcessRequestException {
            ApiClient c = clients.findById(clientId).filter(ApiClient::isActive)
                    .orElseThrow(() -> new ProcessRequestException("client désactivé", SMPPConstant.STAT_ESME_RSUBMITFAIL));
            if (!limiter.allow("smpp:" + clientId, c.getRateLimitPerMin())) throw new ProcessRequestException("quota", SMPPConstant.STAT_ESME_RTHROTTLED);
            String msisdn = Text.normalizeMsisdn(sm.getDestAddress());
            if (msisdn == null) throw new ProcessRequestException("destination", SMPPConstant.STAT_ESME_RINVDSTADR);
            VasService svc = serviceFor(c, sm.getSourceAddr());
            if (svc == null) throw new ProcessRequestException("expéditeur", SMPPConstant.STAT_ESME_RINVSRCADR);
            if (rules.blacklisted(msisdn, svc) || (rules.whitelistSize(svc) > 0 && !rules.whitelisted(msisdn, svc)) || fraud.blocked(msisdn))
                throw new ProcessRequestException("destinataire exclu", SMPPConstant.STAT_ESME_RSUBMITFAIL);
            int coding = sm.getDataCoding() & 0xff;
            byte[] body = sm.getShortMessage() == null ? new byte[0] : sm.getShortMessage();
            OptionalParameter payload = sm.getOptionalParameter(OptionalParameter.Tag.MESSAGE_PAYLOAD);
            if (body.length == 0 && payload instanceof OptionalParameter.OctetString os) body = os.getValue();
            boolean wantDlr = (sm.getRegisteredDelivery() & 0x03) != 0;
            String partId = id();
            if ((sm.getEsmClass() & 0x40) != 0 && body.length > 0) {
                int udhl = body[0] & 0xff;
                int[] ref = concatInfo(body, udhl);
                String text = decode(Arrays.copyOfRange(body, udhl + 1, body.length), coding);
                if (ref == null) return submit(c, svc, msisdn, sm.getSourceAddr(), text, wantDlr, List.of(partId));
                int total = ref[1], seq = ref[2];
                if (total < 1 || total > props.maxParts() || seq < 1 || seq > total) throw new ProcessRequestException("parties", SMPPConstant.STAT_ESME_RINVMSGLEN);
                String key = clientId + "|" + msisdn + "|" + ref[0] + "|" + total;
                Concat cc = concat.computeIfAbsent(key, k -> new Concat(total));
                synchronized (cc) {
                    cc.parts[seq - 1] = text;
                    cc.ids[seq - 1] = partId;
                    if (!cc.complete()) return result(partId);
                    concat.remove(key);
                    submit(c, svc, msisdn, sm.getSourceAddr(), String.join("", cc.parts), wantDlr, List.of(cc.ids));
                    return result(partId);
                }
            }
            return submit(c, svc, msisdn, sm.getSourceAddr(), decode(body, coding), wantDlr, List.of(partId));
        }

        @Override public SubmitMultiResult onAcceptSubmitMulti(SubmitMulti m, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("submit_multi non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public QuerySmResult onAcceptQuerySm(QuerySm q, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("query_sm non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public void onAcceptReplaceSm(ReplaceSm r, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("replace_sm non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public void onAcceptCancelSm(CancelSm c, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("cancel_sm non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public BroadcastSmResult onAcceptBroadcastSm(BroadcastSm b, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public void onAcceptCancelBroadcastSm(CancelBroadcastSm c, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public QueryBroadcastSmResult onAcceptQueryBroadcastSm(QueryBroadcastSm q, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public DataSmResult onAcceptDataSm(DataSm d, Session s) throws ProcessRequestException { throw new ProcessRequestException("data_sm non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
    }

    /** Service actif du partenaire du client dont le short code est l'expéditeur (client sans partenaire : tout service actif). */
    private VasService serviceFor(ApiClient c, String source) {
        if (source == null || source.isBlank()) return null;
        var list = c.getPartner() == null ? services.findAll() : services.findByPartner(c.getPartner());
        return list.stream().filter(s -> s.getStatus() == ServiceStatus.ACTIVE && source.strip().equals(s.getShortCode().getNumber())).findFirst().orElse(null);
    }

    private SubmitSmResult submit(ApiClient c, VasService svc, String msisdn, String sender, String text, boolean wantDlr, List<String> partIds) throws ProcessRequestException {
        if (text.isEmpty()) throw new ProcessRequestException("message vide", SMPPConstant.STAT_ESME_RINVMSGLEN);
        try {
            tx.executeWithoutResult(st -> {
                var m = mtService.submit(new MtService.Request(svc.getShortCode().getOperator(), svc, msisdn, sender, text, Priority.TRANSACTIONAL,
                        "smpp:" + String.join(",", partIds), EventType.MT, null, c.getId(), Duration.ofHours(24)));
                m.setSmppDlr(wantDlr);   // même transaction : l'accusé ne peut pas arriver avant ce marquage (publication après commit)
                mts.save(m);
            });
        } catch (Exception e) {
            log.warn("submit_sm SMPP refusé ({}) : {}", c.getSmppSystemId(), e.getMessage());
            throw new ProcessRequestException("refusé", SMPPConstant.STAT_ESME_RSUBMITFAIL);
        }
        return result(partIds.get(partIds.size() - 1));
    }

    private static SubmitSmResult result(String id) throws ProcessRequestException {
        try {
            return new SubmitSmResult(new MessageId(id), new OptionalParameter[0]);
        } catch (org.jsmpp.PDUStringException e) {
            throw new ProcessRequestException("identifiant", SMPPConstant.STAT_ESME_RSYSERR);
        }
    }

    /** UDH de concaténation : IEI 0x00 (référence 8 bits) ou 0x08 (16 bits) → {référence, total, rang} ; null si absent. */
    static int[] concatInfo(byte[] b, int udhl) {
        int i = 1;
        while (i + 1 < udhl + 1 && i + 1 < b.length) {
            int iei = b[i] & 0xff, len = b[i + 1] & 0xff;
            if (iei == 0x00 && len == 3 && i + 4 < b.length) return new int[]{b[i + 2] & 0xff, b[i + 3] & 0xff, b[i + 4] & 0xff};
            if (iei == 0x08 && len == 4 && i + 5 < b.length) return new int[]{((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff), b[i + 4] & 0xff, b[i + 5] & 0xff};
            i += 2 + len;
        }
        return null;
    }

    static String decode(byte[] b, int coding) {
        return switch (coding) {
            case 8 -> new String(b, StandardCharsets.UTF_16BE);
            case 3 -> new String(b, StandardCharsets.ISO_8859_1);
            default -> MoDecoder.decode(HexFormat.of().formatHex(b), coding, new String(b, StandardCharsets.ISO_8859_1));
        };
    }

    private static String id() {
        byte[] b = new byte[5];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private void expireConcat() {
        long limit = System.currentTimeMillis() - props.concatTimeoutSeconds() * 1000L;
        concat.entrySet().removeIf(e -> {
            if (e.getValue().started >= limit) return false;
            log.warn("serveur SMPP : message concaténé incomplet abandonné ({})", e.getKey().replaceFirst("\\|[^|]+\\|", "|***|"));
            return true;
        });
    }

    // ------------------------------------------------------------------ accusés et MO

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFinal(DomainEvents.MtFinal e) {
        if (running) pool.submit(() -> mts.findByCorrelationId(e.correlationId()).ifPresent(this::deliverDlr));
    }

    private void replayDlr(Long clientId) {
        for (MtMessage m : mts.pendingSmppDlr(clientId)) if (!deliverDlr(m)) break;
    }

    /** Remet l'accusé de chaque partie ; true si remis (ou rien à remettre). */
    synchronized boolean deliverDlr(MtMessage m) {
        if (!m.isSmppDlr() || m.isSmppDlrSent() || m.getApiClientId() == null) return true;
        SMPPServerSession s = receiver(m.getApiClientId());
        if (s == null) return false;   // client déconnecté : rejoué à sa prochaine connexion
        DeliveryReceiptState st = switch (m.getStatus()) {
            case DELIVERED -> DeliveryReceiptState.DELIVRD;
            case EXPIRED -> DeliveryReceiptState.EXPIRED;
            case REJECTED -> DeliveryReceiptState.REJECTD;
            case UNKNOWN -> DeliveryReceiptState.UNKNOWN;
            default -> DeliveryReceiptState.UNDELIV;
        };
        String ref = m.getClientRef() == null ? "" : m.getClientRef().replaceFirst("^smpp:", "");
        try {
            for (String id : ref.split(",")) {
                if (id.isBlank()) continue;
                var r = new DeliveryReceipt(id, 1, st == DeliveryReceiptState.DELIVRD ? 1 : 0, Date.from(m.getCreatedAt()), new Date(), st,
                        st == DeliveryReceiptState.DELIVRD ? "000" : "001", "");
                s.deliverShortMessage("", TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, m.getMsisdn().replace("+", ""), TypeOfNumber.UNKNOWN,
                        NumberingPlanIndicator.UNKNOWN, m.getSender(), new ESMClass(MessageMode.DEFAULT, MessageType.SMSC_DEL_RECEIPT, GSMSpecificFeature.DEFAULT),
                        (byte) 0, (byte) 0, new RegisteredDelivery(0), new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), r.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
            tx.executeWithoutResult(t -> mts.findByCorrelationId(m.getCorrelationId()).ifPresent(x -> { x.setSmppDlrSent(true); mts.save(x); }));
            return true;
        } catch (Exception ex) {
            log.warn("accusé SMPP non remis ({}) : {}", m.getCorrelationId(), ex.getMessage());
            return false;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMo(DomainEvents.MoRouted e) {
        if (!running) return;
        pool.submit(() -> {
            var mo = mos.findById(e.moId()).orElse(null);
            var svc = services.findById(e.serviceId()).orElse(null);
            if (mo == null || svc == null || svc.getPartner() == null) return;
            for (ApiClient c : clients.findByPartnerAndSmppMoTrueAndActiveTrue(svc.getPartner())) {
                SMPPServerSession s = receiver(c.getId());
                if (s == null) continue;
                boolean ucs2 = Text.size(mo.getContent()).encoding() == Text.Encoding.UCS2;
                try {
                    s.deliverShortMessage("", TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, mo.getMsisdn().replace("+", ""), TypeOfNumber.UNKNOWN,
                            NumberingPlanIndicator.UNKNOWN, mo.getShortCode(), new ESMClass(), (byte) 0, (byte) 0, new RegisteredDelivery(0),
                            new GeneralDataCoding(ucs2 ? Alphabet.ALPHA_UCS2 : Alphabet.ALPHA_DEFAULT),
                            mo.getContent().getBytes(ucs2 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1));
                } catch (Exception ex) {
                    log.warn("MO non remis au client SMPP {} ({}) : {}", c.getSmppSystemId(), Views.mask(mo.getMsisdn()), ex.getMessage());
                }
            }
        });
    }

    private SMPPServerSession receiver(Long clientId) {
        var l = bound.get(clientId);
        if (l == null) return null;
        return l.stream().filter(s -> s.getSessionState().isReceivable()).findFirst().orElse(null);
    }
}
