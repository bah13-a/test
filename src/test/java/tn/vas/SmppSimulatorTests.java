package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.jsmpp.bean.*;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.session.*;
import org.jsmpp.util.DeliveryReceiptState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.vas.smppsim.SmppSimulator;

/** Valide le simulateur SMSC avec un vrai client SMPP v3.4 : bind, submit_sm, DLR, MO, throttling, coupure de lien. */
class SmppSimulatorTests {
    SmppSimulator sim;
    int port;
    BlockingQueue<String> dlrs = new LinkedBlockingQueue<>();
    BlockingQueue<String> mos = new LinkedBlockingQueue<>();

    @BeforeEach
    void start() throws Exception {
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        sim = new SmppSimulator(port, "tt", "secret");
        sim.setDlrDelayMs(50);
        sim.start();
    }

    @AfterEach
    void stop() { sim.close(); }

    SMPPSession bind(String password) throws Exception {
        var s = new SMPPSession();
        s.setMessageReceiverListener(new MessageReceiverListener() {
            @Override public void onAcceptDeliverSm(DeliverSm d) {
                String body = new String(d.getShortMessage(), StandardCharsets.ISO_8859_1);
                if (MessageType.SMSC_DEL_RECEIPT.containedIn(d.getEsmClass())) dlrs.add(body); else mos.add(body);
            }
            @Override public void onAcceptAlertNotification(AlertNotification a) { }
            @Override public DataSmResult onAcceptDataSm(DataSm d, Session x) { return null; }
        });
        s.connectAndBind("127.0.0.1", port, new BindParameter(BindType.BIND_TRX, "tt", password, "VAS", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null));
        return s;
    }

    String submit(SMPPSession s) throws Exception {
        return s.submitShortMessage("CMT", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, "1234", TypeOfNumber.INTERNATIONAL,
                NumberingPlanIndicator.ISDN, "21698123456", new ESMClass(), (byte) 0, (byte) 1, null, null,
                new RegisteredDelivery(SMSCDeliveryReceipt.SUCCESS_FAILURE), (byte) 0, new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), (byte) 0,
                "bonjour".getBytes(StandardCharsets.ISO_8859_1)).getMessageId();
    }

    @Test
    void bindSubmitAndDlr() throws Exception {
        var s = bind("secret");
        String id = submit(s);
        assertNotNull(id);
        String dlr = dlrs.poll(3, TimeUnit.SECONDS);
        assertNotNull(dlr, "DLR attendu");
        assertTrue(dlr.contains("id:" + id) && dlr.contains("stat:DELIVRD"), dlr);
        s.unbindAndClose();
    }

    @Test
    void wrongPasswordIsRejected() {
        assertThrows(Exception.class, () -> bind("bad"));
    }

    @Test
    void undeliverableDlrAndMoInjection() throws Exception {
        sim.setDlrStatus(DeliveryReceiptState.UNDELIV);
        var s = bind("secret");
        submit(s);
        assertTrue(dlrs.poll(3, TimeUnit.SECONDS).contains("stat:UNDELIV"));
        assertTrue(sim.injectMo("21698123456", "1234", "VOTE A"));
        assertEquals("VOTE A", mos.poll(3, TimeUnit.SECONDS));
        s.unbindAndClose();
    }

    @Test
    void throttlingReturnsRthrottled() throws Exception {
        sim.setTps(2);
        var s = bind("secret");
        submit(s);
        submit(s);
        var ex = assertThrows(NegativeResponseException.class, () -> submit(s));
        assertEquals(0x58, ex.getCommandStatus()); // ESME_RTHROTTLED
        assertEquals(1, sim.throttled.get());
        s.unbindAndClose();
    }

    @Test
    void linkDropThenRebind() throws Exception {
        var s = bind("secret");
        assertEquals(1, sim.boundSessions());
        sim.dropLinks();
        Thread.sleep(300);
        assertEquals(0, sim.boundSessions());
        s.close();
        var s2 = bind("secret");      // reconnexion après coupure
        assertNotNull(submit(s2));
        assertEquals(2, sim.binds.get());
        s2.unbindAndClose();
    }
}
