package tn.vas;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tn.vas.channel.MenuTree;
import tn.vas.chat.ChatGateways;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.fraud.FraudService;
import tn.vas.repo.Repos.*;
import tn.vas.security.ApiKeyFilter;

/** Options intégrées : antifraude, USSD, IVR, paiement sur facture (DCB), WhatsApp / RCS, informatique décisionnelle. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelsTests {
    static final String PW = "Passw0rd-long-1";
    static final java.util.concurrent.atomic.AtomicInteger SN = new java.util.concurrent.atomic.AtomicInteger(60000 + (int) (Math.random() * 30000));
    static final ObjectMapper JSON = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired OperatorRepo operators;
    @Autowired ShortCodeRepo shortCodes;
    @Autowired ServiceRepo services;
    @Autowired KeywordRepo keywords;
    @Autowired TariffRepo tariffs;
    @Autowired PartnerRepo partners;
    @Autowired MtRepo mts;
    @Autowired MoRepo mos;
    @Autowired LedgerRepo ledger;
    @Autowired ApiClientRepo apiClients;
    @Autowired FraudBlockRepo blocks;
    @Autowired FraudAlertRepo alerts;
    @Autowired ChatChannelRepo chats;
    @Autowired ChatGateways chatGateways;
    @Autowired FraudService fraud;
    @Autowired UserRepo users;
    @Autowired tn.vas.security.UserService userService;

    String sn;
    ShortCode sc;
    VasService vote;

    @BeforeEach
    void setup() {
        tn.vas.security.MsisdnCrypto.configure("test-data-key-0123456789abcdef0123456789abcdef"); // clé statique : un autre contexte Spring (profil dev) a pu la changer
        if (users.findByUsername("admin").isEmpty()) userService.create("admin", PW, java.util.List.of("SUPER_ADMIN"), null);
        sn = String.valueOf(SN.incrementAndGet());
        sc = new ShortCode();
        sc.setNumber(sn);
        sc.setOperator(operators.findByCode("TT").orElseThrow());
        sc = shortCodes.save(sc);
        vote = service("Vote canal " + sn, ServiceType.VOTE, null);
        keyword(vote, "VOTE");
        tariff(vote, EventType.MT, "1.000");
    }

    VasService service(String name, ServiceType type, Partner p) {
        var s = new VasService();
        s.setName(name);
        s.setType(type);
        s.setShortCode(sc);
        s.setStatus(ServiceStatus.ACTIVE);
        s.setConsentMode(ConsentMode.SIMPLE_OPT_IN);
        s.setPartner(p);
        s.setReplyOk("Merci " + name);
        return services.save(s);
    }

    void keyword(VasService s, String w) {
        var k = new Keyword();
        k.setService(s);
        k.setWord(w);
        keywords.save(k);
    }

    void tariff(VasService s, EventType e, String amount) {
        var t = new Tariff();
        t.setService(s);
        t.setEventType(e);
        t.setGrossAmount(new BigDecimal(amount));
        t.setOperatorPercent(new BigDecimal("40"));
        t.setTaxPercent(new BigDecimal("19"));
        t.setEffectiveFrom(Instant.now().minusSeconds(60));
        t.setApproved(true);
        t.setCreatedBy("test");
        tariffs.save(t);
    }

    static String msisdn() { return "9" + String.format("%07d", (int) (Math.random() * 9999999)); }

    String mo(String from, String text) throws Exception {
        return mvc.perform(post("/callbacks/mo").param("secret", "test-secret").param("id", UUID.randomUUID().toString()).param("from", from)
                .param("to", sn).param("content", text).param("origin-connector", "smppc_tt")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ---------------------------------------------------------------- antifraude

    @Test
    void velocityBlocksNumberButStopStillWorks() throws Exception {
        String from = msisdn();
        for (int i = 0; i < 10; i++) mo(from, "BONJOUR" + i);   // mots-clés inconnus : seuls les compteurs de vitesse jouent
        mo(from, "VOTE A");
        var last = mos.findAll().stream().filter(m -> ("+216" + from).equals(m.getMsisdn())).reduce((a, b) -> b).orElseThrow();
        assertEquals(MoOutcome.FRAUD, last.getOutcome());
        assertTrue(fraud.blocked("+216" + from));
        assertTrue(alerts.findTop200ByStatusOrderByIdDesc("OPEN").stream().anyMatch(a -> FraudService.VELOCITY_MINUTE.equals(a.getRule())));
        mo(from, "STOP");   // le désabonnement reste toujours possible
        var stop = mos.findAll().stream().filter(m -> ("+216" + from).equals(m.getMsisdn())).reduce((a, b) -> b).orElseThrow();
        assertEquals(MoOutcome.STOPPED, stop.getOutcome());
        // levée manuelle par l'administrateur
        var block = blocks.active(Instant.now()).stream().filter(b -> ("+216" + from).equals(b.getMsisdn())).findFirst().orElseThrow();
        mvc.perform(delete("/admin/fraud/blocks/" + block.getId()).with(httpBasic("admin", PW))).andExpect(status().isOk());
        assertFalse(fraud.blocked("+216" + from));
        mvc.perform(get("/admin/fraud/alerts").with(httpBasic("admin", PW))).andExpect(status().isOk());
    }

    @Test
    void dailySpendLimitStopsBilledActions() throws Exception {
        var expensive = service("Cher " + sn, ServiceType.VOTE, null);
        keyword(expensive, "CHER");
        tariff(expensive, EventType.MT, "20.000");   // plafond par défaut : 30 TND / jour
        String from = msisdn();
        mo(from, "CHER A");
        mo(from, "CHER B");
        var outcomes = mos.findAll().stream().filter(m -> ("+216" + from).equals(m.getMsisdn())).map(MoMessage::getOutcome).toList();
        assertEquals(MoOutcome.ROUTED, outcomes.get(0));
        assertEquals(MoOutcome.LIMIT_REACHED, outcomes.get(1));
        assertEquals(new BigDecimal("20.000"), ledger.spentSince("+216" + from, Instant.now().minusSeconds(3600)).setScale(3));
    }

    // ---------------------------------------------------------------- USSD / IVR

    static final String MENU = """
            {"text":"Bienvenue\\n1. Voter\\n2. Info payante","options":{
              "1":{"text":"1. Candidat A\\n2. Candidat B","options":{
                    "1":{"text":"Vote A enregistre","action":"KEYWORD","param":"VOTE A"},
                    "2":{"text":"Vote B enregistre","action":"KEYWORD","param":"VOTE B"}}},
              "2":{"text":"Info du jour : beau temps","action":"CHARGE"}}}""";

    void saveMenu(Channel channel, String code) throws Exception {
        String body = JSON.writeValueAsString(java.util.Map.of("channel", channel, "code", code, "serviceId", vote.getId(), "definition", MENU));
        mvc.perform(post("/admin/channels/menus").with(httpBasic("admin", PW)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    String ussd(String session, String from, String code, String text) throws Exception {
        var r = post("/callbacks/ussd").param("secret", "test-secret").param("sessionId", session).param("phoneNumber", from).param("serviceCode", code);
        if (text != null) r = r.param("text", text);
        return mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void ussdMenuRoutesKeywordAndCharges() throws Exception {
        String code = "*1" + sn + "#";
        saveMenu(Channel.USSD, code);
        tariff(vote, EventType.USSD, "0.500");
        String from = msisdn(), s1 = "ussd-" + UUID.randomUUID();
        assertTrue(ussd(s1, from, code, "").startsWith("CON Bienvenue"));
        assertTrue(ussd(s1, from, code, "9").startsWith("CON Choix invalide."));
        assertTrue(ussd(s1, from, code, "1").startsWith("CON 1. Candidat A"));
        assertEquals("END Vote A enregistre", ussd(s1, from, code, "1*1"));
        assertEquals("END " + tn.vas.channel.MenuActions.UNAVAILABLE, ussd(s1, from, code, "1*1"));   // session terminée : renvoi sans effet
        var mo = mos.findAll().stream().filter(m -> ("+216" + from).equals(m.getMsisdn())).toList();
        assertEquals(1, mo.size());
        assertEquals(Channel.USSD, mo.get(0).getChannel());
        assertEquals(MoOutcome.ROUTED, mo.get(0).getOutcome());
        var reply = mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).get(0);
        assertEquals(Channel.SMS, reply.getChannel());   // confirmation par SMS, facturée comme un vote SMS
        assertTrue(reply.isBillable());
        // feuille payante : un seul événement USSD même si la passerelle rejoue
        String s2 = "ussd-" + UUID.randomUUID();
        assertEquals("END Info du jour : beau temps", ussd(s2, from, code, "2"));
        var ev = ledger.findByEventId("USSD-" + s2 + "-2").orElseThrow();
        assertEquals(BillingStatus.CHARGED, ev.getBillingStatus());
        assertEquals(EventType.USSD, ev.getEventType());
        // code USSD inconnu
        assertEquals("END " + tn.vas.channel.MenuActions.UNAVAILABLE, ussd("x-" + UUID.randomUUID(), from, "*999999#", ""));
    }

    @Test
    void menuValidationAndPreview() throws Exception {
        String bad = JSON.writeValueAsString(java.util.Map.of("channel", "USSD", "code", "*5" + sn + "#", "serviceId", vote.getId(), "definition", "{\"text\":\"x\"}"));
        mvc.perform(post("/admin/channels/menus").with(httpBasic("admin", PW)).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isUnprocessableEntity());
        assertThrows(IllegalArgumentException.class, () -> MenuTree.parse("{\"text\":\"a\",\"options\":{\"x\":{\"text\":\"b\"}}}", true));
        assertThrows(IllegalArgumentException.class, () -> MenuTree.parse("{\"text\":\"a\",\"options\":{\"1\":{\"text\":\"b\",\"action\":\"KEYWORD\"}}}", true));
        assertThrows(IllegalArgumentException.class, () -> MenuTree.parse("{\"text\":\"" + "x".repeat(200) + "\",\"options\":{\"1\":{\"text\":\"b\"}}}", true));
        String preview = JSON.writeValueAsString(java.util.Map.of("channel", "USSD", "definition", MENU, "path", "1*2"));
        mvc.perform(post("/admin/channels/menus/preview").with(httpBasic("admin", PW)).contentType(MediaType.APPLICATION_JSON).content(preview))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("KEYWORD")).andExpect(jsonPath("$.param").value("VOTE B"));
    }

    String ivr(String call, String from, String number, String event, String digits, Integer duration) throws Exception {
        var r = post("/callbacks/ivr").param("secret", "test-secret").param("callId", call).param("msisdn", from).param("event", event);
        if (number != null) r = r.param("number", number);
        if (digits != null) r = r.param("digits", digits);
        if (duration != null) r = r.param("duration", duration.toString());
        return mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void ivrMenuAndPerMinuteBilling() throws Exception {
        String number = "80" + sn;
        saveMenu(Channel.IVR, number);
        tariff(vote, EventType.IVR, "0.300");
        String from = msisdn(), call = "call-" + UUID.randomUUID();
        var start = JSON.readTree(ivr(call, from, number, "start", null, null));
        assertEquals("prompt", start.path("action").asText());
        assertTrue(start.path("text").asText().startsWith("Bienvenue"));
        assertEquals("prompt", JSON.readTree(ivr(call, from, null, "input", "1", null)).path("action").asText());
        var end = JSON.readTree(ivr(call, from, null, "input", "2", null));
        assertEquals("hangup", end.path("action").asText());
        assertEquals("Vote B enregistre", end.path("text").asText());
        ivr(call, from, null, "hangup", null, 125);
        ivr(call, from, null, "hangup", null, 125);   // raccroché rejoué : pas de seconde facturation
        var ev = ledger.findByEventId("IVR-" + call).orElseThrow();
        assertEquals(0, new BigDecimal("0.900").compareTo(ev.getGrossAmount()));   // 125 s = 3 minutes entamées × 0,300
        assertEquals(BillingStatus.CHARGED, ev.getBillingStatus());
        assertEquals(1, ledger.findAll().stream().filter(e -> e.getEventId().startsWith("IVR-" + call)).count());
    }

    // ---------------------------------------------------------------- DCB

    String apiKey(Partner p, String scopes) {
        String key = "vas_dcb_" + UUID.randomUUID();
        var c = new ApiClient();
        c.setName("dcb-" + key.substring(8, 16));
        c.setPartner(p);
        c.setScopes(scopes);
        c.setKeyHash(ApiKeyFilter.sha256(key));
        apiClients.save(c);
        return key;
    }

    Partner partner() {
        var p = new Partner();
        p.setName("Partenaire DCB " + sn);
        p.setSharePercent(new BigDecimal("30"));
        return partners.save(p);
    }

    String otpFor(String msisdnE164) {
        var m = mts.findTop100ByMsisdnOrderByCreatedAtDesc(msisdnE164).get(0);
        var matcher = Pattern.compile("Code (\\d{6})").matcher(m.getContent());
        assertTrue(matcher.find(), m.getContent());
        return matcher.group(1);
    }

    @Test
    void dcbOtpChargeRefundAndIsolation() throws Exception {
        var p = partner();
        var shop = service("Boutique " + sn, ServiceType.ALERT, p);
        tariff(shop, EventType.DCB, "2.500");
        String key = apiKey(p, "dcb:charge"), other = apiKey(partner(), "dcb:charge");
        String from = msisdn();
        String body = "{\"msisdn\":\"" + from + "\",\"serviceId\":" + shop.getId() + ",\"clientRef\":\"cmd-" + sn + "\",\"description\":\"Abonnement jeu\"}";
        var res = JSON.readTree(mvc.perform(post("/api/v1/dcb/charges").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String tx = res.path("id").asText();
        assertEquals("OTP_SENT", res.path("status").asText());
        assertEquals(0, new BigDecimal("2.500").compareTo(res.path("amount").decimalValue()));
        // même clientRef : même transaction
        var again = JSON.readTree(mvc.perform(post("/api/v1/dcb/charges").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertEquals(tx, again.path("id").asText());
        // un autre client ne voit pas la transaction
        mvc.perform(get("/api/v1/dcb/charges/" + tx).header("X-API-Key", other)).andExpect(status().isNotFound());
        // code faux puis bon code
        mvc.perform(post("/api/v1/dcb/charges/" + tx + "/confirm").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnprocessableEntity());
        String otp = otpFor("+216" + from);
        mvc.perform(post("/api/v1/dcb/charges/" + tx + "/confirm").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + otp + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHARGED"));
        var ev = ledger.findByEventId("DCB-" + tx).orElseThrow();
        assertEquals(BillingStatus.CHARGED, ev.getBillingStatus());
        assertEquals(0, new BigDecimal("2.500").compareTo(ev.getGrossAmount()));
        // code rejoué : refusé
        mvc.perform(post("/api/v1/dcb/charges/" + tx + "/confirm").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + otp + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/dcb/charges/" + tx + "/refund").header("X-API-Key", key)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        assertEquals(BillingStatus.REVERSED, ledger.findByEventId("DCB-" + tx).orElseThrow().getBillingStatus());
        // scope absent
        mvc.perform(post("/api/v1/dcb/charges").header("X-API-Key", apiKey(p, "messages:send")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/dcb/transactions").with(httpBasic("admin", PW))).andExpect(status().isOk());
    }

    @Test
    void dcbInsufficientBalanceAndOtpAbuse() throws Exception {
        var p = partner();
        var shop = service("Boutique2 " + sn, ServiceType.ALERT, p);
        tariff(shop, EventType.DCB, "1.000");
        String key = apiKey(p, "dcb:charge");
        String poor = "9" + String.format("%03d", (int) (Math.random() * 999)) + "0000";
        var tx = JSON.readTree(mvc.perform(post("/api/v1/dcb/charges").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"msisdn\":\"" + poor + "\",\"serviceId\":" + shop.getId() + "}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/dcb/charges/" + tx + "/confirm").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + otpFor("+216" + poor) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.error").value("INSUFFICIENT_BALANCE"));
        assertTrue(ledger.findByEventId("DCB-" + tx).isEmpty());
        // trois codes faux : échec définitif et numéro bloqué par l'antifraude
        String from = msisdn();
        var tx2 = JSON.readTree(mvc.perform(post("/api/v1/dcb/charges").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"msisdn\":\"" + from + "\",\"serviceId\":" + shop.getId() + "}")).andReturn().getResponse().getContentAsString()).path("id").asText();
        for (int i = 0; i < 2; i++)
            mvc.perform(post("/api/v1/dcb/charges/" + tx2 + "/confirm").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"11111" + i + "\"}"))
                    .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/dcb/charges/" + tx2 + "/confirm").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"999999\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertTrue(fraud.blocked("+216" + from));
        mvc.perform(post("/api/v1/dcb/charges").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"msisdn\":\"" + from + "\",\"serviceId\":" + shop.getId() + "}")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- WhatsApp / RCS

    static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void whatsappInboundRoutesToKeywordAndRepliesOnSameChannel() throws Exception {
        String phoneId = "pn-" + sn;
        var ch = new ChatChannel();
        ch.setKind(Channel.WHATSAPP);
        ch.setAddress(phoneId);
        ch.setShortCode(sc);
        chats.save(ch);
        String from = msisdn();
        String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"" + phoneId + "\"},"
                + "\"messages\":[{\"from\":\"216" + from + "\",\"id\":\"wamid." + sn + "\",\"type\":\"text\",\"text\":{\"body\":\"vote A\"}}]}}]}]}";
        mvc.perform(post("/hooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/hooks/whatsapp").header("X-Hub-Signature-256", sign("faux", body)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/hooks/whatsapp").header("X-Hub-Signature-256", sign("test-app-secret", body)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        var mo = mos.findAll().stream().filter(m -> ("+216" + from).equals(m.getMsisdn())).findFirst().orElseThrow();
        assertEquals(Channel.WHATSAPP, mo.getChannel());
        assertEquals(MoOutcome.ROUTED, mo.getOutcome());
        var reply = mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).get(0);
        assertEquals(Channel.WHATSAPP, reply.getChannel());
        assertFalse(reply.isBillable());   // jamais sur la facture opérateur
        assertTrue(chatGateways.simulated.stream().anyMatch(m -> m.getCorrelationId().equals(reply.getCorrelationId())));
        // statut fournisseur « delivered » → DELIVERED
        var sent = mts.findByCorrelationId(reply.getCorrelationId()).orElseThrow();
        String status = "{\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"" + phoneId + "\"},\"statuses\":[{\"id\":\"" + sent.getSmscMessageId() + "\",\"status\":\"delivered\"}]}}]}]}";
        mvc.perform(post("/hooks/whatsapp").header("X-Hub-Signature-256", sign("test-app-secret", status)).contentType(MediaType.APPLICATION_JSON).content(status)).andExpect(status().isOk());
        assertEquals(MtStatus.DELIVERED, mts.findByCorrelationId(reply.getCorrelationId()).orElseThrow().getStatus());
        // vérification d'abonnement Meta
        mvc.perform(get("/hooks/whatsapp").param("hub.mode", "subscribe").param("hub.verify_token", "test-verify-token").param("hub.challenge", "42"))
                .andExpect(status().isOk()).andExpect(content().string("42"));
        mvc.perform(get("/hooks/whatsapp").param("hub.mode", "subscribe").param("hub.verify_token", "faux").param("hub.challenge", "42")).andExpect(status().isForbidden());
    }

    @Test
    void rcsInboundSigned() throws Exception {
        var ch = new ChatChannel();
        ch.setKind(Channel.RCS);
        ch.setAddress("agent-" + sn);
        ch.setShortCode(sc);
        chats.save(ch);
        String from = msisdn();
        String body = "{\"agentId\":\"agent-" + sn + "\",\"from\":\"+216" + from + "\",\"messageId\":\"r-" + sn + "\",\"text\":\"VOTE B\"}";
        mvc.perform(post("/hooks/rcs").header("X-Signature", sign("test-rcs-secret", body)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertEquals(Channel.RCS, mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).get(0).getChannel());
    }

    // ---------------------------------------------------------------- BI

    @Test
    void analyticsOverviewAndExport() throws Exception {
        mo(msisdn(), "VOTE A");
        mvc.perform(get("/admin/analytics").with(httpBasic("admin", PW))).andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.grossCharged").exists()).andExpect(jsonPath("$.kpis.churnRate").exists())
                .andExpect(jsonPath("$.revenueByDay").isArray()).andExpect(jsonPath("$.channels").isArray())
                .andExpect(jsonPath("$.subscribers.activeByService").isArray());
        mvc.perform(get("/admin/analytics/export").param("dimension", "operator").param("format", "csv").with(httpBasic("admin", PW))).andExpect(status().isOk());
        mvc.perform(get("/admin/analytics").param("from", "2020-01-01").param("to", "2026-01-01").with(httpBasic("admin", PW))).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/analytics/export").param("dimension", "x").with(httpBasic("admin", PW))).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- production

    @Test
    void productionRefusesSimulatorsAndIncompleteOptions() {
        var env = new org.springframework.mock.env.MockEnvironment();
        assertTrue(tn.vas.config.ProductionGuard.validateOptions(env).isEmpty());   // options désactivées par défaut
        env.setProperty("vas.dcb.mode", "simulator");
        env.setProperty("vas.chat.mode", "simulator");
        assertEquals(2, tn.vas.config.ProductionGuard.validateOptions(env).size());
        env.setProperty("vas.dcb.mode", "http");
        env.setProperty("vas.dcb.operators.TT.url", "http://facturation.tt");
        env.setProperty("vas.chat.mode", "live");
        env.setProperty("vas.chat.whatsapp.phone-number-id", "123");
        var errors = String.join(" | ", tn.vas.config.ProductionGuard.validateOptions(env));
        assertTrue(errors.contains("TT_DCB_URL : https"), errors);
        assertTrue(errors.contains("TT_DCB_TOKEN"), errors);
        assertTrue(errors.contains("WHATSAPP_APP_SECRET"), errors);
    }
}
