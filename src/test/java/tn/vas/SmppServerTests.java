package tn.vas;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jsmpp.bean.*;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.session.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

/** Serveur SMPP v3.4 pour clients externes, éprouvé avec un vrai client SMPP (jsmpp). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"vas.smpp-server.enabled=true", "vas.smpp-server.port=27779"})
class SmppServerTests {
    static final String PW = "Passw0rd-long-1";
    static final int PORT = 27779;
    static final ObjectMapper JSON = new ObjectMapper();
    static final java.util.concurrent.atomic.AtomicInteger SN = new java.util.concurrent.atomic.AtomicInteger(30000 + (int) (Math.random() * 9000));
    @Autowired MockMvc mvc;
    @Autowired OperatorRepo operators;
    @Autowired ShortCodeRepo shortCodes;
    @Autowired ServiceRepo services;
    @Autowired KeywordRepo keywords;
    @Autowired TariffRepo tariffs;
    @Autowired PartnerRepo partners;
    @Autowired ApiClientRepo apiClients;
    @Autowired MtRepo mts;
    @Autowired LedgerRepo ledger;
    @Autowired UserRepo users;
    @Autowired tn.vas.security.UserService userService;

    String sn, systemId, password;
    VasService svc;
    ApiClient client;
    BlockingQueue<String> dlrs = new LinkedBlockingQueue<>(), mos = new LinkedBlockingQueue<>();

    @BeforeEach
    void setup() throws Exception {
        tn.vas.security.MsisdnCrypto.configure("test-data-key-0123456789abcdef0123456789abcdef");
        if (users.findByUsername("admin").isEmpty()) userService.create("admin", PW, java.util.List.of("SUPER_ADMIN"), null);
        sn = String.valueOf(SN.incrementAndGet());
        var sc = new ShortCode();
        sc.setNumber(sn);
        sc.setOperator(operators.findByCode("TT").orElseThrow());
        sc = shortCodes.save(sc);
        var p = new Partner();
        p.setName("Agrégateur " + sn);
        p.setSharePercent(new BigDecimal("50"));
        p = partners.save(p);
        svc = new VasService();
        svc.setName("Alertes " + sn);
        svc.setType(ServiceType.VOTE);
        svc.setShortCode(sc);
        svc.setStatus(ServiceStatus.ACTIVE);
        svc.setConsentMode(ConsentMode.SIMPLE_OPT_IN);
        svc.setPartner(p);
        svc.setReplyOk("Merci");
        svc = services.save(svc);
        var k = new Keyword();
        k.setService(svc);
        k.setWord("INFO");
        keywords.save(k);
        var t = new Tariff();
        t.setService(svc); t.setEventType(EventType.MT); t.setGrossAmount(new BigDecimal("0.200")); t.setOperatorPercent(new BigDecimal("40"));
        t.setTaxPercent(new BigDecimal("19")); t.setEffectiveFrom(Instant.now().minusSeconds(60)); t.setApproved(true); t.setCreatedBy("test");
        tariffs.save(t);
        client = new ApiClient();
        client.setName("smpp-" + sn);
        client.setPartner(p);
        client.setScopes("messages:send");
        client.setKeyHash(UUID.randomUUID().toString());
        client = apiClients.save(client);
        systemId = "agg" + sn;
        var res = JSON.readTree(mvc.perform(post("/admin/smpp/clients/" + client.getId()).with(httpBasic("admin", PW)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemId\":\"" + systemId + "\",\"allowedIps\":\"127.0.0.1\",\"mo\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        password = res.path("password").asText();
        assertEquals(8, password.length());
    }

    SMPPSession bind(String sys, String pw, BindType type) throws Exception {
        var s = new SMPPSession();
        s.setMessageReceiverListener(new MessageReceiverListener() {
            @Override public void onAcceptDeliverSm(DeliverSm d) {
                byte[] b = d.getShortMessage();
                String body = (d.getDataCoding() & 0xff) == 8 ? new String(b, StandardCharsets.UTF_16BE) : new String(b, StandardCharsets.ISO_8859_1);
                if (MessageType.SMSC_DEL_RECEIPT.containedIn(d.getEsmClass())) {
                    var rid = d.getOptionalParameter(OptionalParameter.Tag.RECEIPTED_MESSAGE_ID);
                    var st = d.getOptionalParameter(OptionalParameter.Tag.MESSAGE_STATE);
                    dlrs.add(body + (rid == null ? "" : " |tlv:" + ((OptionalParameter.COctetString) rid).getValueAsString() + "/" + ((OptionalParameter.Byte) st).getValue()));
                } else mos.add(d.getSourceAddr() + "|" + body);
            }
            @Override public void onAcceptAlertNotification(AlertNotification a) { }
            @Override public DataSmResult onAcceptDataSm(DataSm d, Session x) { return null; }
        });
        s.connectAndBind("127.0.0.1", PORT, new BindParameter(type, sys, pw, "VAS", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null));
        return s;
    }

    String submit(SMPPSession s, String from, String to, byte esm, byte[] body, Alphabet alphabet) throws Exception {
        return s.submitShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, from, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, to,
                new ESMClass(esm), (byte) 0, (byte) 1, null, null, new RegisteredDelivery(SMSCDeliveryReceipt.SUCCESS_FAILURE), (byte) 0,
                new GeneralDataCoding(alphabet), (byte) 0, body).getMessageId();
    }

    static String msisdn() { return "9" + String.format("%07d", (int) (Math.random() * 9999999)); }

    MtMessage lastMt(String to) {
        return mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + to).get(0);
    }

    void dlr(MtMessage m, String status) throws Exception {
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", m.getCorrelationId()).param("message_status", status)).andExpect(status().isOk());
    }

    @Test
    void submitGoesThroughTheEngineAndDlrComesBack() throws Exception {
        var s = bind(systemId, password, BindType.BIND_TRX);
        String to = msisdn();
        String id = submit(s, sn, "216" + to, (byte) 0, "Alerte : match à 20h".getBytes(StandardCharsets.ISO_8859_1), Alphabet.ALPHA_DEFAULT);
        assertNotNull(id);
        var m = lastMt(to);
        assertEquals(client.getId(), m.getApiClientId());
        assertEquals("Alerte : match à 20h", m.getContent());
        assertTrue(m.isSmppDlr());
        assertTrue(m.isBillable());   // facturé comme un MT de l'API, au tarif approuvé du service
        assertTrue(ledger.findByEventId("MT-" + m.getCorrelationId()).isPresent());
        dlr(m, "DELIVRD");
        String r = dlrs.poll(5, TimeUnit.SECONDS);
        assertNotNull(r, "accusé SMPP attendu");
        assertTrue(r.contains("id:" + id) && r.contains("stat:DELIVRD"), r);
        // arabe (UCS-2)
        String to2 = msisdn();
        submit(s, sn, to2, (byte) 0, "شكرا".getBytes(StandardCharsets.UTF_16BE), Alphabet.ALPHA_UCS2);
        assertEquals("شكرا", lastMt(to2).getContent());
        s.unbindAndClose();
    }

    @Test
    void concatenatedPartsAreReassembledAndEachPartGetsItsReceipt() throws Exception {
        var s = bind(systemId, password, BindType.BIND_TRX);
        String to = msisdn();
        byte[] udh1 = {5, 0, 3, 42, 2, 1}, udh2 = {5, 0, 3, 42, 2, 2};
        String id1 = submit(s, sn, to, (byte) 0x40, concat(udh1, "Premiere partie, ".getBytes(StandardCharsets.ISO_8859_1)), Alphabet.ALPHA_DEFAULT);
        assertTrue(mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + to).isEmpty(), "rien n'est envoyé avant la dernière partie");
        String id2 = submit(s, sn, to, (byte) 0x40, concat(udh2, "seconde partie.".getBytes(StandardCharsets.ISO_8859_1)), Alphabet.ALPHA_DEFAULT);
        var m = lastMt(to);
        assertEquals("Premiere partie, seconde partie.", m.getContent());
        dlr(m, "UNDELIV");
        String a = dlrs.poll(5, TimeUnit.SECONDS), b = dlrs.poll(5, TimeUnit.SECONDS);
        assertNotNull(a); assertNotNull(b);
        assertTrue((a + b).contains("id:" + id1) && (a + b).contains("id:" + id2) && a.contains("stat:UNDELIV"), a + b);
        s.unbindAndClose();
    }

    @Test
    void refusedBindsAndSubmits() throws Exception {
        assertThrows(Exception.class, () -> bind(systemId, "mauvais1", BindType.BIND_TRX));
        assertThrows(Exception.class, () -> bind("inconnu", password, BindType.BIND_TRX));
        var s = bind(systemId, password, BindType.BIND_TRX);
        var e = assertThrows(NegativeResponseException.class, () -> submit(s, "99999", msisdn(), (byte) 0, "x".getBytes(), Alphabet.ALPHA_DEFAULT));
        assertEquals(0x0A, e.getCommandStatus());   // ESME_RINVSRCADR : expéditeur hors des services du client
        var e2 = assertThrows(NegativeResponseException.class, () -> submit(s, sn, "12", (byte) 0, "x".getBytes(), Alphabet.ALPHA_DEFAULT));
        assertEquals(0x0B, e2.getCommandStatus());  // ESME_RINVDSTADR
        // accès retiré : la connexion est fermée et un nouveau bind est refusé
        mvc.perform(delete("/admin/smpp/clients/" + client.getId()).with(httpBasic("admin", PW))).andExpect(status().isOk());
        assertThrows(Exception.class, () -> bind(systemId, password, BindType.BIND_TRX));
    }

    @Test
    void receiptIsReplayedAtReconnectionAndMoIsForwarded() throws Exception {
        var tx = bind(systemId, password, BindType.BIND_TX);
        String to = msisdn();
        String id = submit(tx, sn, to, (byte) 0, "Bonjour".getBytes(StandardCharsets.ISO_8859_1), Alphabet.ALPHA_DEFAULT);
        tx.unbindAndClose();
        dlr(lastMt(to), "DELIVRD");   // client déconnecté : accusé en attente
        assertNull(dlrs.poll(500, TimeUnit.MILLISECONDS));
        var rx = bind(systemId, password, BindType.BIND_RX);
        String r = dlrs.poll(5, TimeUnit.SECONDS);
        assertNotNull(r, "accusé rejoué à la reconnexion");
        assertTrue(r.contains("id:" + id), r);
        // MO d'un abonné vers un service du partenaire : remis en deliver_sm
        String from = msisdn();
        mvc.perform(post("/callbacks/mo").param("secret", "test-secret").param("id", UUID.randomUUID().toString()).param("from", from)
                .param("to", sn).param("content", "INFO foot").param("origin-connector", "smppc_tt")).andExpect(status().isOk());
        String mo = mos.poll(5, TimeUnit.SECONDS);
        assertEquals("216" + from + "|INFO foot", mo);
        rx.unbindAndClose();
    }

    @Test
    void connectionsWithoutBindAreCappedPerAddress() throws Exception {
        var socks = new java.util.ArrayList<java.net.Socket>();
        try {
            for (int i = 0; i < tn.vas.smpp.SmppServer.MAX_PENDING_PER_IP; i++) socks.add(new java.net.Socket("127.0.0.1", PORT));
            Thread.sleep(300);
            var extra = new java.net.Socket("127.0.0.1", PORT);
            extra.setSoTimeout(2000);
            assertEquals(-1, extra.getInputStream().read(), "connexion au-delà du plafond fermée immédiatement");
            extra.close();
        } finally {
            for (var x : socks) x.close();
        }
        Thread.sleep(tn.vas.smpp.SmppServer.BIND_TIMEOUT_MS + 1000);   // les connexions muettes sont libérées au délai de bind
        var s = bind(systemId, password, BindType.BIND_TRX);   // un vrai client se connecte ensuite normalement
        assertTrue(s.getSessionState().isBound());
        s.unbindAndClose();
    }

    String submitAt(SMPPSession s, String to, String text, String schedule, String validity, int registered) throws Exception {
        return s.submitShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, to,
                new ESMClass(), (byte) 0, (byte) 1, schedule, validity, new RegisteredDelivery((byte) registered), (byte) 0,
                new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), (byte) 0, text.getBytes(StandardCharsets.ISO_8859_1)).getMessageId();
    }

    @Test
    void scheduledMessageCanBeQueriedReplacedAndCancelled() throws Exception {
        var s = bind(systemId, password, BindType.BIND_TRX);
        String to = msisdn();
        String id = submitAt(s, to, "Rappel demain", "000000010000000R", null, 1);   // échéance relative : +1 h (§7.1.1.2)
        var m = lastMt(to);
        assertNotNull(m.getScheduledAt());
        assertEquals(MessageState.SCHEDULED, s.queryShortMessage(id, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn).getMessageState());
        s.replaceShortMessage(id, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn, null, null, new RegisteredDelivery(1), (byte) 0,
                "Rappel avance".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("Rappel avance", lastMt(to).getContent());
        // replace_if_present_flag = 1 (§5.2.18) : même source et destination -> le message en attente est remplacé, pas doublé
        String same = s.submitShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, to,
                new ESMClass(), (byte) 0, (byte) 1, "000000010000000R", null, new RegisteredDelivery((byte) 1), (byte) 1,
                new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), (byte) 0, "Rappel final".getBytes(StandardCharsets.ISO_8859_1)).getMessageId();
        assertEquals(id, same);
        assertEquals(1, mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + to).size());
        assertEquals("Rappel final", lastMt(to).getContent());
        s.cancelShortMessage("", id, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, to);
        var q = s.queryShortMessage(id, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn);
        assertEquals(MessageState.DELETED, q.getMessageState());
        assertNotNull(q.getFinalDate());
        assertEquals(BillingStatus.REJECTED, ledger.findByEventId("MT-" + m.getCorrelationId()).orElseThrow().getBillingStatus());   // jamais facturé
        var e = assertThrows(NegativeResponseException.class, () -> s.cancelShortMessage("", id, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn,
                TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, to));
        assertEquals(0x11, e.getCommandStatus());   // ESME_RCANCELFAIL : déjà annulé
        var er = assertThrows(NegativeResponseException.class, () -> s.replaceShortMessage(id, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn,
                null, null, new RegisteredDelivery(1), (byte) 0, "Trop tard".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(0x13, er.getCommandStatus());  // ESME_RREPLACEFAIL : plus en attente
        var e2 = assertThrows(NegativeResponseException.class, () -> s.queryShortMessage("inconnu", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn));
        assertEquals(0x67, e2.getCommandStatus());  // ESME_RQUERYFAIL
        var e3 = assertThrows(NegativeResponseException.class, () -> submitAt(s, msisdn(), "x", null, "991399999999000+", 1));   // 16 caractères mais mois 13
        assertEquals(0x62, e3.getCommandStatus());  // ESME_RINVEXPIRY
        s.unbindAndClose();
    }

    @Test
    void deliveredMessageQueryAndReceiptTlvs() throws Exception {
        var s = bind(systemId, password, BindType.BIND_TRX);
        String to = msisdn();
        String id = submitAt(s, to, "Bonjour", null, "000001000000000R", 1);   // validité relative : 1 jour
        dlr(lastMt(to), "DELIVRD");
        String r = dlrs.poll(5, TimeUnit.SECONDS);
        assertNotNull(r);
        assertTrue(r.endsWith("|tlv:" + id + "/" + MessageState.DELIVERED.value()), r);   // receipted_message_id + message_state
        var q = s.queryShortMessage(id, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn);
        assertEquals(MessageState.DELIVERED, q.getMessageState());
        // registered_delivery = 2 : accusé sur échec seulement
        String ok = msisdn(), ko = msisdn();
        submitAt(s, ok, "A", null, null, 2);
        String idKo = submitAt(s, ko, "B", null, null, 2);
        dlr(lastMt(ok), "DELIVRD");
        dlr(lastMt(ko), "UNDELIV");
        String only = dlrs.poll(5, TimeUnit.SECONDS);
        assertNotNull(only);
        assertTrue(only.contains("id:" + idKo) && only.contains("stat:UNDELIV"), only);
        assertNull(dlrs.poll(1, TimeUnit.SECONDS), "aucun accusé pour le message livré");
        s.unbindAndClose();
    }

    @Test
    void submitMultiDataSmAndSarConcatenation() throws Exception {
        var s = bind(systemId, password, BindType.BIND_TRX);
        String a = msisdn(), b = msisdn();
        var res = s.submitMultiple("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn,
                new Address[]{new Address(TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "216" + a),
                        new Address(TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, "216" + b),
                        new Address(TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, "12")},
                new ESMClass(), (byte) 0, (byte) 1, null, null, new RegisteredDelivery(0), ReplaceIfPresentFlag.DEFAULT, new GeneralDataCoding(Alphabet.ALPHA_DEFAULT),
                (byte) 0, "Info groupe".getBytes(StandardCharsets.ISO_8859_1));
        assertNotNull(res.getMessageId());
        assertEquals(1, res.getUnsuccessDeliveries().length);
        assertEquals(0x0B, res.getUnsuccessDeliveries()[0].getErrorStatusCode());   // destination invalide
        assertEquals("Info groupe", lastMt(a).getContent());
        assertEquals("Info groupe", lastMt(b).getContent());
        // data_sm (§4.7) : texte dans message_payload, alphabet IA5 (data_coding 1)
        String c = msisdn();
        var d = s.dataShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, c,
                new ESMClass(), new RegisteredDelivery(0), new GeneralDataCoding(Alphabet.ALPHA_IA5),
                new OptionalParameter.OctetString(OptionalParameter.Tag.MESSAGE_PAYLOAD.code(), "Message data_sm".getBytes(StandardCharsets.US_ASCII)));
        assertNotNull(d.getMessageId());
        assertEquals("Message data_sm", lastMt(c).getContent());
        // concaténation par TLV SAR (§5.3.2.22-24) au lieu de l'UDH
        String e = msisdn();
        for (int i = 1; i <= 2; i++)
            s.submitShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sn, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, e,
                    new ESMClass(), (byte) 0, (byte) 1, null, null, new RegisteredDelivery(0), (byte) 0, new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), (byte) 0,
                    (i == 1 ? "Partie un, " : "partie deux.").getBytes(StandardCharsets.ISO_8859_1),
                    new OptionalParameter.Short(OptionalParameter.Tag.SAR_MSG_REF_NUM, (short) 77),
                    new OptionalParameter.Byte(OptionalParameter.Tag.SAR_TOTAL_SEGMENTS, (byte) 2),
                    new OptionalParameter.Byte(OptionalParameter.Tag.SAR_SEGMENT_SEQNUM, (byte) i));
        assertEquals("Partie un, partie deux.", lastMt(e).getContent());
        s.unbindAndClose();
    }

    @Test
    void smppTimeFormats() {
        var now = Instant.parse("2026-10-16T10:00:00Z");
        assertEquals(Instant.parse("2026-10-16T11:30:00Z"), tn.vas.smpp.SmppTime.parse("000000013000000R", now));
        assertEquals(Instant.parse("2026-10-16T09:00:00Z"), tn.vas.smpp.SmppTime.parse("261016100000004+", now));   // UTC+1 (4 quarts d'heure)
        assertNull(tn.vas.smpp.SmppTime.parse("", now));
        assertThrows(IllegalArgumentException.class, () -> tn.vas.smpp.SmppTime.parse("2026-10-16", now));
        assertEquals("261016100000000+", tn.vas.smpp.SmppTime.format(now));
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
