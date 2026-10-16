package tn.vas.smpp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.jsmpp.DefaultPDUReader;
import org.jsmpp.DefaultPDUSender;
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
    /** Connexions en attente de bind (plafonnées) : une connexion sans bind ne mobilise un fil que BIND_TIMEOUT_MS. */
    public static final int MAX_PENDING = 50, MAX_PENDING_PER_IP = 5, BIND_TIMEOUT_MS = 3_000, PDU_TIMEOUT_MS = 5_000, MAX_DELIVERY_FAILURES = 3;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "smpp-server"); t.setDaemon(true); return t; });
    /** Remise des accusés et MO : pool borné ; file pleine = accusé laissé en base, rejoué à la reconnexion (rien n'est perdu). */
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10_000),
            r -> { var t = new Thread(r, "smpp-delivery"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.DiscardPolicy());
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> pendingByIp = new ConcurrentHashMap<>();
    /** Un verrou par client : un client lent ne retarde jamais les accusés des autres. */
    private final ConcurrentHashMap<Long, Object> clientLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> deliveryFailures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "smpp-concat"); t.setDaemon(true); return t; });
    /** Connexions ouvertes par client API. */
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SMPPServerSession>> bound = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SMPPServerSession, Long> owner = new ConcurrentHashMap<>();
    /** Parties de messages concaténés en attente : clé client|destination|référence. */
    private final ConcurrentHashMap<String, Concat> concat = new ConcurrentHashMap<>();
    private volatile org.jsmpp.session.connection.ServerConnection listener;
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
            listener = new org.jsmpp.session.connection.socket.ServerSocketConnectionFactory().listen(props.port());
        } catch (IOException e) {
            throw new IllegalStateException("serveur SMPP : port " + props.port() + " indisponible", e);
        }
        running = true;
        pool.submit(() -> {
            while (running) {
                try {
                    var conn = listener.accept();
                    conn.setSoTimeout(5000);
                    SMPPServerSession s = new SMPPServerSession(conn, null, null, null, 3, 100, new StatusSender(), new DefaultPDUReader());
                    String ip = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
                    var perIp = pendingByIp.computeIfAbsent(ip, k -> new java.util.concurrent.atomic.AtomicInteger());
                    if (pending.incrementAndGet() > MAX_PENDING || perIp.incrementAndGet() > MAX_PENDING_PER_IP) {
                        pending.decrementAndGet();
                        perIp.decrementAndGet();
                        s.close();   // trop de connexions sans bind : refus immédiat (inondation de connexions)
                        continue;
                    }
                    pool.submit(() -> {
                        try { bind(s); } finally { pending.decrementAndGet(); perIp.decrementAndGet(); }
                    });
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
        delivery.shutdownNow();
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
            BindRequest br = s.waitForBind(BIND_TIMEOUT_MS);
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
            s.setTransactionTimer(PDU_TIMEOUT_MS);   // un client qui ne répond pas aux deliver_sm n'immobilise pas la remise plus de 5 s
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
            deliveryFailures.remove(c.getId());
            if (br.getBindType() != BindType.BIND_TX) delivery.execute(() -> replayDlr(c.getId()));
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

    // ------------------------------------------------------------------ PDU SMPP v3.4 reçus du client (§4.4 à §4.10)

    /** Champs communs à submit_sm, data_sm et submit_multi (une destination). */
    record Sm(String source, String dest, int esm, int coding, byte[] body, OptionalParameter payload, int registered, int priority,
              String schedule, String validity, OptionalParameter sarRef, OptionalParameter sarTotal, OptionalParameter sarSeq, boolean replace) {}

    private final class Receiver implements ServerMessageReceiverListener {
        private final long clientId;

        Receiver(long clientId) { this.clientId = clientId; }

        private ApiClient client() throws ProcessRequestException {
            return clients.findById(clientId).filter(ApiClient::isActive).filter(x -> scope(x, "messages:send") && x.getSmppSystemId() != null)
                    .orElseThrow(() -> new ProcessRequestException("client désactivé ou droit retiré", SMPPConstant.STAT_ESME_RSUBMITFAIL));
        }

        @Override
        public SubmitSmResult onAcceptSubmitSm(SubmitSm sm, SMPPServerSession session) throws ProcessRequestException {
            var c = client();
            quota(c);
            return result(accept(c, new Sm(sm.getSourceAddr(), sm.getDestAddress(), sm.getEsmClass() & 0xff, sm.getDataCoding() & 0xff, sm.getShortMessage(),
                    sm.getOptionalParameter(OptionalParameter.Tag.MESSAGE_PAYLOAD), sm.getRegisteredDelivery() & 0xff, sm.getPriorityFlag() & 0xff,
                    sm.getScheduleDeliveryTime(), sm.getValidityPeriod(), sm.getOptionalParameter(OptionalParameter.Tag.SAR_MSG_REF_NUM),
                    sm.getOptionalParameter(OptionalParameter.Tag.SAR_TOTAL_SEGMENTS), sm.getOptionalParameter(OptionalParameter.Tag.SAR_SEGMENT_SEQNUM),
                    sm.getReplaceIfPresent() == 1), null));
        }

        /** data_sm (§4.7) : même traitement, texte dans message_payload, pas de date d'échéance (validité par défaut). */
        @Override
        public DataSmResult onAcceptDataSm(DataSm d, Session s) throws ProcessRequestException {
            var c = client();
            quota(c);
            String id = accept(c, new Sm(d.getSourceAddr(), d.getDestAddress(), d.getEsmClass() & 0xff, d.getDataCoding() & 0xff, null,
                    d.getOptionalParameter(OptionalParameter.Tag.MESSAGE_PAYLOAD), d.getRegisteredDelivery() & 0xff, 0, null, null,
                    d.getOptionalParameter(OptionalParameter.Tag.SAR_MSG_REF_NUM), d.getOptionalParameter(OptionalParameter.Tag.SAR_TOTAL_SEGMENTS),
                    d.getOptionalParameter(OptionalParameter.Tag.SAR_SEGMENT_SEQNUM), false), null);
            try {
                return new DataSmResult(new MessageId(id), new OptionalParameter[0]);
            } catch (org.jsmpp.PDUStringException e) {
                throw new ProcessRequestException("identifiant", SMPPConstant.STAT_ESME_RSYSERR);
            }
        }

        /** submit_multi (§4.5) : jusqu'à 255 destinations, un identifiant commun, destinations refusées listées avec leur code. */
        @Override
        public SubmitMultiResult onAcceptSubmitMulti(SubmitMulti m, SMPPServerSession s) throws ProcessRequestException {
            var c = client();
            var dests = m.getDestAddresses() == null ? new DestinationAddress[0] : m.getDestAddresses();
            if (dests.length == 0 || dests.length > 255) throw new ProcessRequestException("nombre de destinations", SMPPConstant.STAT_ESME_RINVNUMDESTS);
            String id = id();
            var refused = new ArrayList<UnsuccessDelivery>();
            int accepted = 0;
            for (DestinationAddress d : dests) {
                if (!(d instanceof Address a)) {   // liste de distribution (dl_name) : non prise en charge
                    refused.add(new UnsuccessDelivery((byte) 0, (byte) 0, "", SMPPConstant.STAT_ESME_RINVDLNAME));
                    continue;
                }
                try {
                    quota(c);
                    accept(c, new Sm(m.getSourceAddr(), a.getAddress(), m.getEsmClass() & 0xff, m.getDataCoding() & 0xff, m.getShortMessage(),
                            m.getOptionalParameter(OptionalParameter.Tag.MESSAGE_PAYLOAD), m.getRegisteredDelivery() & 0xff, m.getPriorityFlag() & 0xff,
                            m.getScheduleDeliveryTime(), m.getValidityPeriod(), null, null, null, false), id);
                    accepted++;
                } catch (ProcessRequestException e) {
                    refused.add(new UnsuccessDelivery(a, e.getErrorCode()));
                }
            }
            if (accepted == 0 && !refused.isEmpty()) throw new ProcessRequestException("aucune destination acceptée", refused.get(0).getErrorStatusCode());
            return new SubmitMultiResult(id, refused.toArray(new UnsuccessDelivery[0]), new OptionalParameter[0]);
        }

        /** query_sm (§4.8) : état du message, date finale, code d'erreur. L'expéditeur doit être celui du message d'origine. */
        @Override
        public QuerySmResult onAcceptQuerySm(QuerySm q, SMPPServerSession s) throws ProcessRequestException {
            STATUS.remove();
            client();
            MtMessage m = mts.findSmpp(clientId, q.getMessageId()).stream().filter(x -> owns(x, q.getMessageId())).findFirst()
                    .filter(x -> q.getSourceAddr() == null || q.getSourceAddr().isBlank() || q.getSourceAddr().equals(x.getSender()))
                    .orElseThrow(() -> refuse("message inconnu", SMPPConstant.STAT_ESME_RQUERYFAIL));
            MessageState st = state(m);
            boolean fin = st != MessageState.ENROUTE && st != MessageState.SCHEDULED && st != MessageState.ACCEPTED;
            return new QuerySmResult(fin ? SmppTime.format(m.getUpdatedAt()) : null, st, (byte) 0);
        }

        /** cancel_sm (§4.9) : par message_id, ou tous les messages en attente d'un expéditeur vers une destination. */
        @Override
        public void onAcceptCancelSm(CancelSm cs, SMPPServerSession s) throws ProcessRequestException {
            STATUS.remove();
            client();
            List<MtMessage> targets;
            if (cs.getMessageId() != null && !cs.getMessageId().isBlank()) {
                targets = mts.findSmpp(clientId, cs.getMessageId()).stream().filter(x -> owns(x, cs.getMessageId())).toList();
            } else {
                String dest = Text.normalizeMsisdn(cs.getDestinationAddress());
                targets = mts.pendingFrom(clientId, cs.getSourceAddr()).stream().filter(x -> dest == null || dest.equals(x.getMsisdn())).toList();
            }
            if (targets.isEmpty()) throw refuse("message inconnu", SMPPConstant.STAT_ESME_RCANCELFAIL);
            boolean any = false;
            for (MtMessage m : targets) any |= mtService.cancelPending(m.getCorrelationId());
            if (!any) throw refuse("message déjà transmis", SMPPConstant.STAT_ESME_RCANCELFAIL);
        }

        /** replace_sm (§4.10) : texte, échéance et validité d'un message encore en attente. */
        @Override
        public void onAcceptReplaceSm(ReplaceSm r, SMPPServerSession s) throws ProcessRequestException {
            STATUS.remove();
            client();
            MtMessage m = mts.findSmpp(clientId, r.getMessageId()).stream().filter(x -> owns(x, r.getMessageId())).findFirst()
                    .filter(x -> r.getSourceAddr() == null || r.getSourceAddr().isBlank() || r.getSourceAddr().equals(x.getSender()))
                    .orElseThrow(() -> refuse("message inconnu", SMPPConstant.STAT_ESME_RREPLACEFAIL));
            var now = java.time.Instant.now();
            java.time.Instant sched, valid;
            try {
                sched = SmppTime.parse(r.getScheduleDeliveryTime(), now);
                valid = SmppTime.parse(r.getValidityPeriod(), now);
            } catch (IllegalArgumentException e) {
                throw refuse(e.getMessage(), SMPPConstant.STAT_ESME_RINVSCHED);
            }
            String text = r.getShortMessage() == null || r.getShortMessage().length == 0 ? null : decode(r.getShortMessage(), "UCS2".equals(m.getEncoding()) ? 8 : 0);   // replace_sm ne porte pas de data_coding : celui du message d'origine
            if (!mtService.replacePending(m.getCorrelationId(), text, sched, valid)) throw refuse("message déjà transmis", SMPPConstant.STAT_ESME_RREPLACEFAIL);
        }

        @Override public BroadcastSmResult onAcceptBroadcastSm(BroadcastSm b, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("broadcast_sm (SMPP 5.0) non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public void onAcceptCancelBroadcastSm(CancelBroadcastSm c, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("SMPP 5.0 non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public QueryBroadcastSmResult onAcceptQueryBroadcastSm(QueryBroadcastSm q, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("SMPP 5.0 non pris en charge", SMPPConstant.STAT_ESME_RINVCMDID); }

        private void quota(ApiClient c) throws ProcessRequestException {
            if (!limiter.allow("smpp:" + clientId, c.getRateLimitPerMin())) throw new ProcessRequestException("quota", SMPPConstant.STAT_ESME_RTHROTTLED);
        }

        /** Contrôles et envoi d'une destination ; renvoie l'identifiant rendu au client (commun si sharedId, ex. submit_multi). */
        private String accept(ApiClient c, Sm sm, String sharedId) throws ProcessRequestException {
            String msisdn = Text.normalizeMsisdn(sm.dest());
            if (msisdn == null) throw new ProcessRequestException("destination", SMPPConstant.STAT_ESME_RINVDSTADR);
            VasService svc = serviceFor(c, sm.source());
            if (svc == null) throw new ProcessRequestException("expéditeur", SMPPConstant.STAT_ESME_RINVSRCADR);
            if (rules.blacklisted(msisdn, svc) || (rules.whitelistSize(svc) > 0 && !rules.whitelisted(msisdn, svc)) || fraud.blocked(msisdn))
                throw new ProcessRequestException("destinataire exclu", SMPPConstant.STAT_ESME_RSUBMITFAIL);
            var now = java.time.Instant.now();
            java.time.Instant sched, valid;
            try { sched = SmppTime.parse(sm.schedule(), now); } catch (IllegalArgumentException e) { throw new ProcessRequestException(e.getMessage(), SMPPConstant.STAT_ESME_RINVSCHED); }
            try { valid = SmppTime.parse(sm.validity(), now); } catch (IllegalArgumentException e) { throw new ProcessRequestException(e.getMessage(), SMPPConstant.STAT_ESME_RINVEXPIRY); }
            if (sched != null && sched.isAfter(now.plus(Duration.ofDays(30)))) throw new ProcessRequestException("échéance > 30 jours", SMPPConstant.STAT_ESME_RINVSCHED);
            java.time.Instant start = sched != null && sched.isAfter(now) ? sched : now;
            if (valid != null && !valid.isAfter(start)) throw new ProcessRequestException("validité dépassée", SMPPConstant.STAT_ESME_RINVEXPIRY);
            Duration validity = valid == null ? Duration.ofHours(24) : Duration.between(start, valid).compareTo(Duration.ofDays(7)) > 0 ? Duration.ofDays(7) : Duration.between(start, valid);
            Priority prio = sm.priority() >= 2 ? Priority.TRANSACTIONAL : sm.priority() == 1 ? Priority.CONFIRMATION : Priority.BULK;
            int mode = sm.registered() & 0x03;
            byte[] body = sm.body() == null ? new byte[0] : sm.body();
            if (body.length == 0 && sm.payload() instanceof OptionalParameter.OctetString os) body = os.getValue();
            String partId = sharedId != null ? sharedId : id();
            int[] ref = null;
            String text;
            if ((sm.esm() & 0x40) != 0 && body.length > 0) {   // UDH (UDHI)
                int udhl = body[0] & 0xff;
                ref = concatInfo(body, udhl);
                text = decode(Arrays.copyOfRange(body, Math.min(body.length, udhl + 1), body.length), sm.coding());
            } else {
                text = decode(body, sm.coding());
                if (sm.sarRef() != null && sm.sarTotal() != null && sm.sarSeq() != null)   // TLV SAR (§5.3.2.22-24)
                    ref = new int[]{num(sm.sarRef()), num(sm.sarTotal()), num(sm.sarSeq())};
            }
            if (ref == null && sm.replace()) {   // replace_if_present_flag (§5.2.18) : remplace le dernier message en attente même source / destination
                var prev = mts.pendingFrom(clientId, sm.source()).stream().filter(x -> msisdn.equals(x.getMsisdn()))
                        .max(Comparator.comparing(MtMessage::getId)).orElse(null);
                if (prev != null && prev.getClientRef() != null
                        && mtService.replacePending(prev.getCorrelationId(), text, sched, start.plus(validity)))
                    return prev.getClientRef().replaceFirst("^smpp:", "").split(",")[0];
            }
            if (ref == null) {
                submit(c, svc, msisdn, sm.source(), text, mode, List.of(partId), prio, sched, validity);
                return partId;
            }
            int total = ref[1], seq = ref[2];
            if (total < 1 || total > props.maxParts() || seq < 1 || seq > total) throw new ProcessRequestException("parties", SMPPConstant.STAT_ESME_RINVMSGLEN);
            String key = clientId + "|" + msisdn + "|" + ref[0] + "|" + total;
            Concat cc = concat.computeIfAbsent(key, k -> new Concat(total));
            synchronized (cc) {
                cc.parts[seq - 1] = text;
                cc.ids[seq - 1] = partId;
                if (!cc.complete()) return partId;
                concat.remove(key);
                submit(c, svc, msisdn, sm.source(), String.join("", cc.parts), mode, List.of(cc.ids), prio, sched, validity);
                return partId;
            }
        }
    }

    private static int num(OptionalParameter p) {
        if (p instanceof OptionalParameter.Short sh) return sh.getValue() & 0xffff;
        if (p instanceof OptionalParameter.Byte by) return by.getValue() & 0xff;
        byte[] b = p.serialize();
        return b.length >= 5 ? (b[4] & 0xff) : 0;
    }

    /** L'identifiant demandé fait bien partie de ceux rendus pour ce message (évite les correspondances partielles du LIKE). */
    private static boolean owns(MtMessage m, String id) {
        return id != null && m.getClientRef() != null && Arrays.asList(m.getClientRef().replaceFirst("^smpp:", "").split(",")).contains(id);
    }

    /** message_state SMPP v3.4 (§5.2.28) d'un MT. */
    static MessageState state(MtMessage m) {
        return switch (m.getStatus()) {
            case PENDING -> m.getScheduledAt() != null && m.getScheduledAt().isAfter(java.time.Instant.now()) ? MessageState.SCHEDULED : MessageState.ENROUTE;
            case SUBMITTED -> MessageState.ENROUTE;
            case DELIVERED -> MessageState.DELIVERED;
            case EXPIRED -> MessageState.EXPIRED;
            case REJECTED -> "CANCELLED".equals(m.getRawStatus()) ? MessageState.DELETED : MessageState.REJECTED;
            case UNKNOWN -> MessageState.UNKNOWN;
            default -> MessageState.UNDELIVERABLE;
        };
    }

    /** Service actif du partenaire du client dont le short code est l'expéditeur (client sans partenaire : tout service actif). */
    private VasService serviceFor(ApiClient c, String source) {
        if (source == null || source.isBlank()) return null;
        var list = c.getPartner() == null ? services.findAll() : services.findByPartner(c.getPartner());
        return list.stream().filter(s -> s.getStatus() == ServiceStatus.ACTIVE && source.strip().equals(s.getShortCode().getNumber())).findFirst().orElse(null);
    }

    private void submit(ApiClient c, VasService svc, String msisdn, String sender, String text, int dlrMode, List<String> partIds, Priority prio,
                        java.time.Instant schedule, Duration validity) throws ProcessRequestException {
        if (text.isEmpty()) throw new ProcessRequestException("message vide", SMPPConstant.STAT_ESME_RINVMSGLEN);
        try {
            tx.executeWithoutResult(st -> {
                var m = mtService.submit(new MtService.Request(svc.getShortCode().getOperator(), svc, msisdn, sender, text, prio,
                        "smpp:" + String.join(",", partIds), EventType.MT, null, c.getId(), validity, null, schedule));
                m.setSmppDlr(dlrMode != 0);   // même transaction : l'accusé ne peut pas arriver avant ce marquage (publication après commit)
                m.setSmppDlrMode((short) dlrMode);
                mts.save(m);
            });
        } catch (Exception e) {
            log.warn("submit_sm SMPP refusé ({}) : {}", c.getSmppSystemId(), e.getMessage());
            throw new ProcessRequestException("refusé", SMPPConstant.STAT_ESME_RSUBMITFAIL);
        }
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

    /**
     * jsmpp 3.0.0 répond toujours ESME_RSYSERR (0x08) à un query_sm / cancel_sm / replace_sm refusé, quel que soit le code levé
     * par l'écouteur. Le code exact (§5.1.3 : 0x67, 0x11, 0x13, 0x61…) est mémorisé sur le fil de traitement du PDU
     * (le même que celui qui écrit la réponse) et rétabli ici, au moment de l'écriture de l'en-tête de réponse.
     */
    private static final ThreadLocal<Integer> STATUS = new ThreadLocal<>();

    private static ProcessRequestException refuse(String msg, int code) {
        STATUS.set(code);
        return new ProcessRequestException(msg, code);
    }

    static final class StatusSender extends DefaultPDUSender {
        @Override
        public byte[] sendHeader(java.io.OutputStream os, int commandId, int status, int seq) throws IOException {
            Integer real = STATUS.get();
            int cmd = commandId & ~SMPPConstant.MASK_CID_RESP;
            if (real != null && (cmd == SMPPConstant.CID_QUERY_SM || cmd == SMPPConstant.CID_CANCEL_SM || cmd == SMPPConstant.CID_REPLACE_SM)) {
                STATUS.remove();
                if (status == SMPPConstant.STAT_ESME_RSYSERR) status = real;
            }
            return super.sendHeader(os, commandId, status, seq);
        }
    }

    static String decode(byte[] b, int coding) {
        return switch (coding) {
            case 8 -> new String(b, StandardCharsets.UTF_16BE);
            case 3 -> new String(b, StandardCharsets.ISO_8859_1);
            case 1 -> new String(b, StandardCharsets.US_ASCII);
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
        if (running) delivery.execute(() -> mts.findByCorrelationId(e.correlationId()).ifPresent(this::deliverDlr));
    }

    private void replayDlr(Long clientId) {
        for (MtMessage m : mts.pendingSmppDlr(clientId)) if (!deliverDlr(m)) break;
    }

    /** Remet l'accusé de chaque partie ; true si remis (ou rien à remettre). */
    boolean deliverDlr(MtMessage m) {
        if (!m.isSmppDlr() || m.isSmppDlrSent() || m.getApiClientId() == null) return true;
        synchronized (clientLocks.computeIfAbsent(m.getApiClientId(), k -> new Object())) {
            return deliverDlrLocked(m);
        }
    }

    private boolean deliverDlrLocked(MtMessage m) {
        if (mts.findByCorrelationId(m.getCorrelationId()).map(MtMessage::isSmppDlrSent).orElse(true)) return true;   // déjà remis par un autre fil
        if (m.getSmppDlrMode() == 2 && m.getStatus() == MtStatus.DELIVERED) {   // accusé demandé sur échec seulement
            tx.executeWithoutResult(t -> mts.findByCorrelationId(m.getCorrelationId()).ifPresent(x -> { x.setSmppDlrSent(true); mts.save(x); }));
            return true;
        }
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
                        (byte) 0, (byte) 0, new RegisteredDelivery(0), new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), r.toString().getBytes(StandardCharsets.ISO_8859_1),
                        // TLV d'accusé SMPP v3.4 (§5.3.2.12, §5.3.2.35) : identifiant et état du message d'origine
                        new OptionalParameter.COctetString(OptionalParameter.Tag.RECEIPTED_MESSAGE_ID.code(), id),
                        new OptionalParameter.Byte(OptionalParameter.Tag.MESSAGE_STATE, state(m).value()));
            }
            tx.executeWithoutResult(t -> mts.findByCorrelationId(m.getCorrelationId()).ifPresent(x -> { x.setSmppDlrSent(true); mts.save(x); }));
            deliveryFailures.remove(m.getApiClientId());
            return true;
        } catch (Exception ex) {
            log.warn("accusé SMPP non remis ({}) : {}", m.getCorrelationId(), ex.getMessage());
            // client qui ne répond plus : déconnecté après quelques échecs ; ses accusés restent en base et sont rejoués à sa reconnexion
            if (deliveryFailures.computeIfAbsent(m.getApiClientId(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet() >= MAX_DELIVERY_FAILURES) {
                log.warn("client SMPP {} déconnecté : remises en échec répétées", m.getApiClientId());
                disconnect(m.getApiClientId());
                deliveryFailures.remove(m.getApiClientId());
            }
            return false;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMo(DomainEvents.MoRouted e) {
        if (!running) return;
        delivery.execute(() -> {
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
