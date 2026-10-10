package tn.vas.smppsim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jsmpp.PDUStringException;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.*;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.session.*;
import org.jsmpp.util.DeliveryReceiptState;
import org.jsmpp.util.MessageIDGenerator;
import org.jsmpp.util.RandomMessageIDGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simulateur de SMSC SMPP v3.4 (CDC §13.3) : bind (TX/RX/TRX) avec authentification, submit_sm, enquire_link, DLR configurables,
 * throttling (ESME_RTHROTTLED), coupure de lien et injection de MO. Se lance seul (main) ou s'embarque dans les tests.
 *
 * Variables : SIM_SMPP_PORT (2776), SIM_CONTROL_PORT (8081), SIM_SYSTEM_ID, SIM_PASSWORD, SIM_DLR_STATUS (DELIVRD), SIM_DLR_DELAY_MS (200), SIM_TPS (0 = illimité).
 */
public class SmppSimulator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SmppSimulator.class);

    private final int port;
    private final String systemId;
    private final String password;
    private volatile DeliveryReceiptState dlrStatus = DeliveryReceiptState.DELIVRD;
    private volatile long dlrDelayMs = 200;
    private volatile int tps = 0;
    private final MessageIDGenerator ids = new RandomMessageIDGenerator();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "smpp-sim"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> { var t = new Thread(r, "smpp-sim-dlr"); t.setDaemon(true); return t; });
    private final CopyOnWriteArrayList<SMPPServerSession> sessions = new CopyOnWriteArrayList<>();
    private SMPPServerSessionListener listener;
    private volatile boolean running;
    private HttpServer control;
    public final AtomicInteger submitted = new AtomicInteger(), throttled = new AtomicInteger(), binds = new AtomicInteger();
    /** Journal des submit_sm reçus (contrôle de l'encodage et de la segmentation par le test d'intégration). */
    public final java.util.concurrent.ConcurrentLinkedDeque<Map<String, Object>> submitLog = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final AtomicLong windowStart = new AtomicLong();
    private final AtomicInteger windowCount = new AtomicInteger();

    public SmppSimulator(int port, String systemId, String password) {
        this.port = port;
        this.systemId = systemId;
        this.password = password;
    }

    public static void main(String[] a) throws Exception {
        var sim = new SmppSimulator(Integer.parseInt(env("SIM_SMPP_PORT", "2776")), env("SIM_SYSTEM_ID", "sim"), env("SIM_PASSWORD", "sim"));
        sim.dlrStatus = DeliveryReceiptState.valueOf(env("SIM_DLR_STATUS", "DELIVRD"));
        sim.dlrDelayMs = Long.parseLong(env("SIM_DLR_DELAY_MS", "200"));
        sim.tps = Integer.parseInt(env("SIM_TPS", "0"));
        sim.start();
        sim.startControl(Integer.parseInt(env("SIM_CONTROL_PORT", "8081")));
        Thread.currentThread().join();
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    public void start() throws IOException {
        listener = new SMPPServerSessionListener(port);
        running = true;
        pool.submit(() -> {
            while (running) {
                try {
                    SMPPServerSession s = listener.accept();
                    pool.submit(() -> handle(s));
                } catch (IOException e) {
                    if (running) log.warn("accept : {}", e.getMessage());
                }
            }
        });
        log.info("SMSC simulé en écoute sur {}", port);
    }

    private void handle(SMPPServerSession s) {
        try {
            BindRequest br = s.waitForBind(10_000);
            if (!systemId.equals(br.getSystemId())) { br.reject(SMPPConstant.STAT_ESME_RINVSYSID); return; }
            if (!password.equals(br.getPassword())) { br.reject(SMPPConstant.STAT_ESME_RINVPASWD); return; }
            br.accept(systemId, InterfaceVersion.IF_34);
            binds.incrementAndGet();
            s.setMessageReceiverListener(new Receiver());
            sessions.add(s);
            s.addSessionStateListener((n, o, src) -> { if (!n.isBound()) sessions.remove(s); });
        } catch (Exception e) {
            log.warn("bind échoué : {}", e.getMessage());
            s.close();
        }
    }

    private boolean overLimit() {
        if (tps <= 0) return false;
        long now = System.currentTimeMillis();
        long ws = windowStart.get();
        if (now - ws >= 1000 && windowStart.compareAndSet(ws, now)) windowCount.set(0);
        return windowCount.incrementAndGet() > tps;
    }

    private class Receiver implements ServerMessageReceiverListener {
        @Override
        public SubmitSmResult onAcceptSubmitSm(SubmitSm sm, SMPPServerSession session) throws ProcessRequestException {
            if (overLimit()) {
                throttled.incrementAndGet();
                throw new ProcessRequestException("throttled", SMPPConstant.STAT_ESME_RTHROTTLED);
            }
            submitted.incrementAndGet();
            var id = ids.newMessageId();
            int dc = sm.getDataCoding() & 0xff;
            byte[] body = sm.getShortMessage();
            boolean udh = (sm.getEsmClass() & 0x40) != 0;
            int off = udh && body.length > 0 ? (body[0] & 0xff) + 1 : 0;
            String text = dc == 8 ? new String(body, off, body.length - off, StandardCharsets.UTF_16BE) : new String(body, off, body.length - off, StandardCharsets.ISO_8859_1);
            var entry = new java.util.LinkedHashMap<String, Object>();
            entry.put("id", id.getValue()); entry.put("from", sm.getSourceAddr()); entry.put("to", sm.getDestAddress()); entry.put("dataCoding", dc);
            entry.put("udh", udh); entry.put("bytes", body.length); entry.put("text", text); entry.put("registeredDelivery", (int) sm.getRegisteredDelivery());
            submitLog.addFirst(entry);
            while (submitLog.size() > 500) submitLog.removeLast();
            if (sm.getRegisteredDelivery() != 0 && dlrStatus != null) {
                String src = sm.getSourceAddr(), dst = sm.getDestAddress();
                timer.schedule(() -> sendDlr(session, id.getValue(), src, dst, dlrStatus), dlrDelayMs, TimeUnit.MILLISECONDS);
            }
            return new SubmitSmResult(id, new OptionalParameter[0]);
        }

        @Override public SubmitMultiResult onAcceptSubmitMulti(SubmitMulti m, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("unsupported", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public QuerySmResult onAcceptQuerySm(QuerySm q, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("unsupported", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public void onAcceptReplaceSm(ReplaceSm r, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("unsupported", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public void onAcceptCancelSm(CancelSm c, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("unsupported", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public BroadcastSmResult onAcceptBroadcastSm(BroadcastSm b, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("unsupported", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public void onAcceptCancelBroadcastSm(CancelBroadcastSm c, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("unsupported", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public QueryBroadcastSmResult onAcceptQueryBroadcastSm(QueryBroadcastSm q, SMPPServerSession s) throws ProcessRequestException { throw new ProcessRequestException("unsupported", SMPPConstant.STAT_ESME_RINVCMDID); }
        @Override public DataSmResult onAcceptDataSm(DataSm d, org.jsmpp.session.Session s) throws ProcessRequestException { throw new ProcessRequestException("unsupported", SMPPConstant.STAT_ESME_RINVCMDID); }
    }

    private void sendDlr(SMPPServerSession session, String msgId, String from, String to, DeliveryReceiptState state) {
        try {
            var r = new DeliveryReceipt(msgId, 1, state == DeliveryReceiptState.DELIVRD ? 1 : 0, new Date(), new Date(), state,
                    state == DeliveryReceiptState.DELIVRD ? "000" : "001", "");
            // le récepteur d'un DLR : adresse source = destinataire du MT, destination = expéditeur d'origine
            session.deliverShortMessage("CMT", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, to, TypeOfNumber.UNKNOWN,
                    NumberingPlanIndicator.UNKNOWN, from, new ESMClass(MessageMode.DEFAULT, MessageType.SMSC_DEL_RECEIPT, GSMSpecificFeature.DEFAULT),
                    (byte) 0, (byte) 0, new RegisteredDelivery(0), new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), r.toString().getBytes(StandardCharsets.ISO_8859_1));
        } catch (Exception e) {
            log.warn("DLR non délivré : {}", e.getMessage());
        }
    }

    /** Injecte un MO (deliver_sm) vers la première session liée en réception. */
    public boolean injectMo(String from, String shortCode, String text) throws Exception {
        return injectMo(from, shortCode, text, false);
    }

    /** @param ucs2 true : data_coding 8 (UCS-2, arabe) ; false : alphabet par défaut. */
    public boolean injectMo(String from, String shortCode, String text, boolean ucs2) throws Exception {
        for (var s : sessions) {
            if (s.getSessionState().isReceivable()) {
                s.deliverShortMessage("CMT", TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, from, TypeOfNumber.NATIONAL, NumberingPlanIndicator.UNKNOWN,
                        shortCode, new ESMClass(), (byte) 0, (byte) 0, new RegisteredDelivery(0),
                        new GeneralDataCoding(ucs2 ? Alphabet.ALPHA_UCS2 : Alphabet.ALPHA_DEFAULT),
                        text.getBytes(ucs2 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1));
                return true;
            }
        }
        return false;
    }

    /** Coupe brutalement tous les liens (simulation d'un incident réseau). */
    public void dropLinks() {
        for (var s : sessions) s.close();
        sessions.clear();
    }

    public void setDlrStatus(DeliveryReceiptState s) { this.dlrStatus = s; }
    public void setDlrDelayMs(long ms) { this.dlrDelayMs = ms; }
    public void setTps(int tps) { this.tps = tps; }
    public int boundSessions() { return sessions.size(); }

    /** API de contrôle HTTP : POST /mo?from=&to=&text= , /drop , /tps?v= , /dlr?status= ; GET /stats. */
    public void startControl(int controlPort) throws IOException {
        control = HttpServer.create(new InetSocketAddress(controlPort), 0);
        control.createContext("/", ex -> {
            try {
                Map<String, String> q = query(ex);
                String path = ex.getRequestURI().getPath();
                String out = switch (path) {
                    case "/mo" -> String.valueOf(injectMo(q.get("from"), q.get("to"), q.get("text"), "8".equals(q.get("coding"))));
                    case "/messages" -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(submitLog);
                    case "/reset" -> { submitLog.clear(); submitted.set(0); throttled.set(0); yield "ok"; }
                    case "/drop" -> { dropLinks(); yield "dropped"; }
                    case "/tps" -> { setTps(Integer.parseInt(q.get("v"))); yield "ok"; }
                    case "/dlr" -> { setDlrStatus(DeliveryReceiptState.valueOf(q.get("status"))); yield "ok"; }
                    case "/stats" -> "{\"submitted\":" + submitted + ",\"throttled\":" + throttled + ",\"binds\":" + binds + ",\"bound\":" + boundSessions() + "}";
                    default -> "unknown";
                };
                reply(ex, 200, out);
            } catch (Exception e) {
                reply(ex, 500, String.valueOf(e.getMessage()));
            }
        });
        control.start();
    }

    private static Map<String, String> query(HttpExchange ex) {
        var m = new java.util.HashMap<String, String>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) for (String kv : q.split("&")) {
            String[] p = kv.split("=", 2);
            m.put(java.net.URLDecoder.decode(p[0], StandardCharsets.UTF_8), p.length > 1 ? java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        running = false;
        dropLinks();
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        if (control != null) control.stop(0);
        pool.shutdownNow();
        timer.shutdownNow();
    }
}
