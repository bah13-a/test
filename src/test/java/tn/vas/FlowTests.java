package tn.vas;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.gateway.SimulatorGateway;
import tn.vas.repo.Repos.*;
import tn.vas.security.ApiKeyFilter;
import tn.vas.service.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FlowTests {
    @Autowired MockMvc mvc;
    @Autowired OperatorRepo operators;
    @Autowired ShortCodeRepo shortCodes;
    @Autowired PartnerRepo partners;
    @Autowired ServiceRepo services;
    @Autowired KeywordRepo keywords;
    @Autowired TariffRepo tariffs;
    @Autowired MtRepo mts;
    @Autowired LedgerRepo ledger;
    @Autowired ApiClientRepo apiClients;
    @Autowired SubscriptionRepo subs;
    @Autowired SimulatorGateway sim;
    @Autowired MtSweeper sweeper;
    @Autowired SubscriptionService subscriptionService;
    @Autowired tn.vas.security.UserService userService;
    @Autowired UserRepo userRepo;
    @Autowired ReplyRepo replies;
    @Autowired RuleRepo rules;
    static final String PW = "Passw0rd-long-1";

    Operator tt;
    ShortCode sc;
    VasService vote;
    VasService abo;
    static final java.util.concurrent.atomic.AtomicInteger SN = new java.util.concurrent.atomic.AtomicInteger(10000 + (int) (Math.random() * 40000));
    String sn;           // préfixe unique par test (isolation sans nettoyage de base)

    void user(String name, String role, Partner partner) {
        if (userRepo.findByUsername(name).isEmpty()) userService.create(name, PW, java.util.List.of(role), partner);
    }

    @BeforeEach
    void setup() {
        user("admin", "SUPER_ADMIN", null);
        user("finance", "FINANCE", null);
        user("finance2", "FINANCE", null);
        sn = String.valueOf(SN.incrementAndGet()); // compteur : jamais deux short codes identiques (le tirage aléatoire entrait en collision)
        tt = operators.findByCode("TT").orElseThrow();
        sc = new ShortCode();
        sc.setNumber(sn);
        sc.setOperator(tt);
        sc = shortCodes.save(sc);
        vote = newService("Vote " + sn, ServiceType.VOTE, ConsentMode.SIMPLE_OPT_IN, null);
        keyword(vote, "VOTE");
        tariff(vote, EventType.MT, "1.000");
        abo = newService("Abo " + sn, ServiceType.SUBSCRIPTION, ConsentMode.SIMPLE_OPT_IN, null);
        keyword(abo, "ABO");
        tariff(abo, EventType.SUBSCRIPTION, "2.000");
    }

    VasService newService(String name, ServiceType type, ConsentMode mode, Partner partner) {
        var s = new VasService();
        s.setName(name);
        s.setType(type);
        s.setShortCode(sc);
        s.setStatus(ServiceStatus.ACTIVE);
        s.setConsentMode(mode);
        s.setPartner(partner);
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

    String msisdn() { return "9" + String.format("%07d", (int) (Math.random() * 9999999)); }

    void mo(String id, String from, String content) throws Exception {
        mvc.perform(post("/callbacks/mo").param("secret", "test-secret").param("id", id).param("from", from)
                .param("to", sn).param("content", content).param("origin-connector", "smppc_tt"))
                .andExpect(status().isOk()).andExpect(content().string("ACK/Jasmin"));
    }

    MtMessage lastMt(String msisdnE164) {
        return mts.findTop100ByMsisdnOrderByCreatedAtDesc(msisdnE164).get(0);
    }

    @Test
    void callbacksRequireSecret() throws Exception {
        mvc.perform(post("/callbacks/mo").param("from", "98123456").param("to", sn).param("content", "VOTE A")
                .param("origin-connector", "smppc_tt")).andExpect(status().isForbidden());
    }

    @Test
    void voteFlow_moMtDlr_idempotentBilling() throws Exception {
        String from = msisdn();
        String id = UUID.randomUUID().toString();
        mo(id, from, "vote A");
        var m = lastMt("+216" + from);
        assertEquals(MtStatus.SUBMITTED, m.getStatus());
        assertEquals(sn, m.getSender());
        assertTrue(m.isBillable());
        var ev = ledger.findByEventId("MT-" + m.getCorrelationId()).orElseThrow();
        assertEquals(BillingStatus.PENDING, ev.getBillingStatus());
        assertEquals(0, new BigDecimal("1.000").compareTo(ev.getGrossAmount()));

        // MO rejoué (même message_id) → aucun nouvel effet
        mo(id, from, "vote A");
        assertEquals(1, mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).size());

        // DLR livré → CHARGED ; DLR rejoué ou contradictoire → aucun changement
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", m.getCorrelationId())
                .param("message_status", "DELIVRD")).andExpect(status().isOk());
        assertEquals(BillingStatus.CHARGED, ledger.findByEventId("MT-" + m.getCorrelationId()).orElseThrow().getBillingStatus());
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", m.getCorrelationId())
                .param("message_status", "UNDELIV")).andExpect(status().isOk());
        assertEquals(MtStatus.DELIVERED, mts.findByCorrelationId(m.getCorrelationId()).orElseThrow().getStatus());
        assertEquals(BillingStatus.CHARGED, ledger.findByEventId("MT-" + m.getCorrelationId()).orElseThrow().getBillingStatus());
    }

    @Test
    void undeliveredMtIsNotCharged() throws Exception {
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "VOTE B");
        var m = lastMt("+216" + from);
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", m.getCorrelationId())
                .param("message_status", "UNDELIV")).andExpect(status().isOk());
        assertEquals(BillingStatus.REJECTED, ledger.findByEventId("MT-" + m.getCorrelationId()).orElseThrow().getBillingStatus());
    }

    @Test
    void unknownKeywordIsSilentlyRejected() throws Exception {
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "BLABLA");
        assertTrue(mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).isEmpty());
    }

    @Test
    void subscriptionStopBlocksRenewal() throws Exception {
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "ABO");
        var sub = subs.findByMsisdnAndService("+216" + from, abo).orElseThrow();
        assertEquals(SubStatus.ACTIVE, sub.getStatus());
        mo(UUID.randomUUID().toString(), from, "STOP");
        sub = subs.findByMsisdnAndService("+216" + from, abo).orElseThrow();
        assertEquals(SubStatus.STOPPED, sub.getStatus());
        assertNull(sub.getNextRenewalAt());
        int before = mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).size();
        subscriptionService.renewDue();
        assertEquals(before, mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).size());
    }

    @Test
    void doubleOptInRequiresConfirmation() throws Exception {
        var dbl = newService("Double " + sn, ServiceType.SUBSCRIPTION, ConsentMode.DOUBLE_OPT_IN, null);
        keyword(dbl, "JOIN");
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "JOIN");
        assertEquals(SubStatus.PENDING_CONFIRMATION, subs.findByMsisdnAndService("+216" + from, dbl).orElseThrow().getStatus());
        mo(UUID.randomUUID().toString(), from, "OUI");
        assertEquals(SubStatus.ACTIVE, subs.findByMsisdnAndService("+216" + from, dbl).orElseThrow().getStatus());
    }

    @Test
    void regulatedServiceBlockedUntilApproved() throws Exception {
        var game = newService("Jeu " + sn, ServiceType.QUIZ, ConsentMode.SIMPLE_OPT_IN, null);
        game.setRegulated(true);
        services.save(game);
        keyword(game, "QUIZ");
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "QUIZ 1");
        assertTrue(mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).isEmpty());
        game.setRegulatoryApproved(true);
        services.save(game);
        mo(UUID.randomUUID().toString(), from, "QUIZ 2");
        assertEquals(1, mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).size());
    }

    @Test
    void linkDown_noMessageLost_sweeperRetries() throws Exception {
        sim.failNext(1);
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "VOTE C");
        var m = lastMt("+216" + from);
        assertEquals(MtStatus.PENDING, m.getStatus()); // lien coupé : conservé, pas perdu
        assertEquals(1, m.getAttempts());
        sweeper.sweep();
        m = mts.findByCorrelationId(m.getCorrelationId()).orElseThrow();
        assertEquals(MtStatus.SUBMITTED, m.getStatus());
    }

    @Test
    void apiKeyScopesAndPartnerIsolation() throws Exception {
        var p1 = partners.save(partner("P1"));
        var p2 = partners.save(partner("P2"));
        var s1 = newService("S1 " + sn, ServiceType.ALERT, ConsentMode.SIMPLE_OPT_IN, p1);
        var s2 = newService("S2 " + sn, ServiceType.ALERT, ConsentMode.SIMPLE_OPT_IN, p2);
        String key = "vas_testkey_" + sn;
        var c = new ApiClient();
        c.setName("c1");
        c.setPartner(p1);
        c.setScopes("messages:send,messages:read,services:read");
        c.setKeyHash(ApiKeyFilter.sha256(key));
        c = apiClients.save(c);

        mvc.perform(get("/api/v1/services")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/services").header("X-API-Key", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        String body = "{\"to\":\"98123456\",\"text\":\"Bonjour\",\"serviceId\":" + s1.getId() + ",\"clientRef\":\"ref-" + sn + "\"}";
        mvc.perform(post("/api/v1/messages").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")); // accepté en file ; l'envoi est asynchrone
        // idempotence clientRef : même requête → même message, pas de second envoi
        mvc.perform(post("/api/v1/messages").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertEquals(1, mts.findTop100ByMsisdnOrderByCreatedAtDesc("+21698123456").stream()
                .filter(x -> ("ref-" + sn).equals(x.getClientRef())).count());
        // service d'un autre partenaire → invisible
        mvc.perform(post("/api/v1/messages").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"serviceId\":" + s1.getId(), "\"serviceId\":" + s2.getId()).replace("ref-", "x-")))
                .andExpect(status().isNotFound());
        // scope manquant
        mvc.perform(get("/api/v1/reports").header("X-API-Key", key)).andExpect(status().isForbidden());
    }

    @Test
    void adminRbacAndFourEyesTariff() throws Exception {
        mvc.perform(get("/admin/operators")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/audit").with(httpBasic("finance", PW))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/audit").with(httpBasic("admin", PW))).andExpect(status().isOk());

        String json = "{\"serviceId\":" + vote.getId() + ",\"eventType\":\"MT\",\"grossAmount\":0.5,\"operatorPercent\":40,\"taxPercent\":19}";
        var res = mvc.perform(post("/admin/tariffs").with(httpBasic("finance", PW))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk()).andReturn();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/admin/tariffs/" + id + "/approve").with(httpBasic("finance", PW))).andExpect(status().isForbidden());
        // un tarif non approuvé est corrigeable ; approuvé, il devient immuable
        mvc.perform(patch("/admin/tariffs/" + id).with(httpBasic("finance", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"grossAmount\":0.75}")).andExpect(status().isOk());
        mvc.perform(post("/admin/tariffs/" + id + "/approve").with(httpBasic("finance2", PW))).andExpect(status().isOk());
        mvc.perform(patch("/admin/tariffs/" + id).with(httpBasic("finance", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"grossAmount\":9}")).andExpect(status().isConflict());
        mvc.perform(delete("/admin/tariffs/" + id).with(httpBasic("finance", PW))).andExpect(status().isConflict());
    }

    Partner partner(String n) {
        var p = new Partner();
        p.setName(n);
        return p;
    }

    @Test
    void arabicMoGetsArabicReply() throws Exception {
        String from = msisdn();
        var rep = new ServiceReply();
        rep.setService(vote); rep.setLang("ar"); rep.setKind("OK"); rep.setText("شكرا لتصويتك");
        replies.save(rep);
        mo(UUID.randomUUID().toString(), from, "VOTE أ");
        var m = lastMt("+216" + from);
        assertEquals("شكرا لتصويتك", m.getContent());
        assertEquals("UCS2", m.getEncoding());
        // sans surcharge, le catalogue système répond en arabe pour un STOP arabe
        String from2 = msisdn();
        mo(UUID.randomUUID().toString(), from2, "ABO");
        mo(UUID.randomUUID().toString(), from2, "إلغاء");
        assertTrue(lastMt("+216" + from2).getContent().contains("تم إلغاء"));
    }

    @Test
    void blacklistBlocksAndWhitelistRestricts() throws Exception {
        String from = msisdn();
        var r = new MsisdnRule();
        r.setMsisdn("+216" + from); r.setRuleType("BLACK"); r.setService(vote); r.setCreatedAt(Instant.now());
        rules.save(r);
        mo(UUID.randomUUID().toString(), from, "VOTE A");
        assertTrue(mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + from).isEmpty());
        // liste blanche : seuls les numéros listés passent
        String friend = msisdn();
        var w = new MsisdnRule();
        w.setMsisdn("+216" + friend); w.setRuleType("WHITE"); w.setService(vote); w.setCreatedAt(Instant.now());
        rules.save(w);
        String other = msisdn();
        mo(UUID.randomUUID().toString(), other, "VOTE A");
        assertTrue(mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + other).isEmpty());
        mo(UUID.randomUUID().toString(), friend, "VOTE A");
        assertEquals(1, mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + friend).size());
    }

    @Test
    void mfaEnrollmentAndEnforcement() throws Exception {
        user("mfauser", "SUPPORT", null);
        var basic = httpBasic("mfauser", PW);
        mvc.perform(post("/admin/me/mfa/setup").with(basic)).andExpect(status().isOk());
        String secret = userRepo.findByUsername("mfauser").orElseThrow().getTotpSecret();
        String code = tn.vas.security.Totp.generate(secret, System.currentTimeMillis() / 30000);
        mvc.perform(post("/admin/me/mfa/confirm").with(basic).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/me/mfa/confirm").with(basic).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        // MFA actif : mot de passe seul refusé, avec code TOTP accepté
        mvc.perform(get("/admin/operators").with(basic)).andExpect(status().isUnauthorized()).andExpect(header().string("X-MFA-Required", "true"));
        String code2 = tn.vas.security.Totp.generate(secret, System.currentTimeMillis() / 30000);
        mvc.perform(get("/admin/operators").with(basic).header("X-TOTP", code2)).andExpect(status().isOk());
    }

    @Test
    void partnerPortalIsolation() throws Exception {
        var pa = partners.save(partner("PortalA"));
        var pb = partners.save(partner("PortalB"));
        var sa = newService("SA " + sn, ServiceType.VOTE, ConsentMode.SIMPLE_OPT_IN, pa);
        keyword(sa, "PA");
        var sb = newService("SB " + sn, ServiceType.VOTE, ConsentMode.SIMPLE_OPT_IN, pb);
        user("partnera", "PARTNER", pa);
        mo(UUID.randomUUID().toString(), msisdn(), "PA 1");
        mo(UUID.randomUUID().toString(), msisdn(), "PA 2");
        var basic = httpBasic("partnera", PW);
        mvc.perform(get("/portal/services").with(basic)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/portal/results").param("serviceId", sa.getId().toString()).with(basic)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/portal/results").param("serviceId", sb.getId().toString()).with(basic)).andExpect(status().isNotFound());
        assertTrue(auditRepo.findAll().stream().anyMatch(a -> a.getAction().equals("CROSS_ACCOUNT_ACCESS_DENIED") && a.getTarget().equals("service:" + sb.getId())));
        mvc.perform(get("/portal/summary").with(basic)).andExpect(status().isOk()).andExpect(jsonPath("$.partner").value("PortalA"));
        mvc.perform(get("/admin/operators").with(basic)).andExpect(status().isForbidden());
        assertTrue(auditRepo.findAll().stream().anyMatch(a -> a.getAction().equals("ACCESS_DENIED") && a.getTarget().equals("GET /admin/operators")));
        mvc.perform(get("/portal/summary").with(httpBasic("admin", PW))).andExpect(status().isForbidden());
    }

    @Test
    void reconciliationXlsxAndCsv() throws Exception {
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "VOTE Z");
        var m = lastMt("+216" + from);
        String eventId = "MT-" + m.getCorrelationId();
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", m.getCorrelationId()).param("message_status", "DELIVRD"));
        // relevé XLSX : montant correct sur l'événement, une ligne inconnue de la plateforme
        byte[] xlsx;
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var sh = wb.createSheet();
            var h = sh.createRow(0);
            h.createCell(0).setCellValue("Reference"); h.createCell(1).setCellValue("Montant"); h.createCell(2).setCellValue("Etat");
            var r1 = sh.createRow(1);
            r1.createCell(0).setCellValue(eventId); r1.createCell(1).setCellValue(1.0); r1.createCell(2).setCellValue("CHARGED");
            var r2 = sh.createRow(2);
            r2.createCell(0).setCellValue("MT-unknown"); r2.createCell(1).setCellValue(1.0); r2.createCell(2).setCellValue("CHARGED");
            wb.write(out);
            xlsx = out.toByteArray();
        }
        var file = new org.springframework.mock.web.MockMultipartFile("file", "releve.xlsx", "application/octet-stream", xlsx);
        var res = mvc.perform(multipart("/admin/reconciliation/TT").file(file).with(httpBasic("finance", PW))
                .param("from", Instant.now().minusSeconds(3600).toString()).param("to", Instant.now().plusSeconds(3600).toString())
                .param("idColumn", "Reference").param("amountColumn", "Montant").param("statusColumn", "Etat"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.MATCHED").value(1))
                .andExpect(jsonPath("$.summary.MISSING_ON_PLATFORM").value(1)).andReturn();
        String batch = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getResponse().getContentAsString()).get("batch").asText();
        // export des écarts en PDF et XLSX
        mvc.perform(get("/admin/reconciliation/" + batch + "/export").param("format", "pdf").with(httpBasic("finance", PW)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/admin/ledger/export").param("format", "xlsx").with(httpBasic("finance", PW))).andExpect(status().isOk());
        mvc.perform(get("/admin/ledger/export").param("format", "csv").with(httpBasic("finance", PW))).andExpect(status().isOk());
    }

    @Test
    void partnerWebhookReceivesMoEvent() throws Exception {
        var p = partner("Hook");
        p.setWebhookUrl("http://localhost:9/hook");
        p.setWebhookSecret("s3cret");
        p = partners.save(p);
        var s = newService("Hooked " + sn, ServiceType.VOTE, ConsentMode.SIMPLE_OPT_IN, p);
        keyword(s, "HK");
        mo(UUID.randomUUID().toString(), msisdn(), "HK 1");
        assertTrue(webhooks.findAll().stream().anyMatch(w -> w.getEventId().startsWith("MO-") && w.getUrl().endsWith("/hook")));
    }

    @Autowired WebhookRepo webhooks;
    @Autowired AuditRepo auditRepo;
    @Autowired MtSweeper mtSweeper;
    @Autowired VoteOptionRepo voteOptions;
    @Autowired QuizQuestionRepo quizQuestions;
    @Autowired ContentItemRepo contentItems;

    private VasService typed(String name, ServiceType type, String keyword) {
        var s = newService(name + " " + sn, type, ConsentMode.SIMPLE_OPT_IN, null);
        keyword(s, keyword);
        tariff(s, EventType.MT, "0.500");
        return s;
    }

    @Test
    void voteWithDeclaredOptionsRejectsInvalidChoiceAndPublishesOfficialResults() throws Exception {
        var v = typed("VoteOpt", ServiceType.VOTE, "VOTEOPT");
        for (String[] o : new String[][]{{"A", "Alice"}, {"B", "Bob"}}) { var x = new VoteOption(); x.setService(v); x.setCode(o[0]); x.setLabel(o[1]); voteOptions.save(x); }
        String f1 = msisdn(), f2 = msisdn(), f3 = msisdn();
        mo(UUID.randomUUID().toString(), f1, "VOTEOPT A");
        mo(UUID.randomUUID().toString(), f2, "voteopt a");
        mo(UUID.randomUUID().toString(), f3, "VOTEOPT B");
        // choix invalide ou absent : réponse explicative, jamais facturée, bulletin non compté
        String f4 = msisdn();
        mo(UUID.randomUUID().toString(), f4, "VOTEOPT Z");
        var bad = lastMt("+216" + f4);
        assertTrue(bad.getContent().contains("A, B"), bad.getContent());
        assertFalse(bad.isBillable());
        mo(UUID.randomUUID().toString(), msisdn(), "VOTEOPT");
        var adminBasic = httpBasic("admin", PW);
        mvc.perform(get("/admin/services/" + v.getId() + "/campaign").with(adminBasic)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].count").value(2)).andExpect(jsonPath("$.results[0].detail").value("66.7 %"))
                .andExpect(jsonPath("$.participants").value(5)).andExpect(jsonPath("$.mo.INVALID_CHOICE").value(2));
        // clôture : résultats officiels figés, plus aucun vote accepté
        mvc.perform(post("/admin/services/" + v.getId() + "/close").with(adminBasic)).andExpect(status().isOk()).andExpect(jsonPath("$.results[0].content").value("A — Alice"));
        mvc.perform(post("/admin/services/" + v.getId() + "/close").with(adminBasic)).andExpect(status().isConflict());
        String f5 = msisdn();
        mo(UUID.randomUUID().toString(), f5, "VOTEOPT A");
        mvc.perform(get("/admin/services/" + v.getId() + "/campaign").with(adminBasic)).andExpect(jsonPath("$.results[0].count").value(2)).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(get("/admin/services/" + v.getId() + "/campaign/export").param("format", "pdf").with(adminBasic)).andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/admin/services/" + v.getId() + "/campaign/export").param("format", "xlsx").with(adminBasic)).andExpect(status().isOk());
    }

    @Test
    void quizAsksQuestionsInSequenceScoresAnswersAndAllowsOnePlay() throws Exception {
        var q = typed("Quiz", ServiceType.QUIZ, "QUIZGO");
        for (int i = 1; i <= 3; i++) {
            var x = new QuizQuestion();
            x.setService(q); x.setPosition(i); x.setPoints(i == 3 ? 2 : 1);
            x.setQuestion(new String[]{"", "Capitale de la Tunisie ?", "2+2 ?", "Capitale de l'Égypte ?"}[i]);
            x.setAnswers(new String[]{"", "Tunis|تونس", "4|quatre", "Le Caire|القاهرة"}[i]);
            quizQuestions.save(x);
        }
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "QUIZGO");
        assertTrue(lastMt("+216" + from).getContent().contains("Question 1/3 : Capitale de la Tunisie ?"));
        assertTrue(lastMt("+216" + from).isBillable());
        mo(UUID.randomUUID().toString(), from, "  tunis ");                       // casse et espaces ignorés
        var second = lastMt("+216" + from).getContent();
        assertTrue(second.contains("Bonne réponse") && second.contains("Question 2/3 : 2+2 ?"), second);
        mo(UUID.randomUUID().toString(), from, "cinq");                           // mauvaise réponse
        assertTrue(lastMt("+216" + from).getContent().contains("Mauvaise réponse") && lastMt("+216" + from).getContent().contains("Question 3/3"));
        mo(UUID.randomUUID().toString(), from, "le caire");                       // 2 points
        assertTrue(lastMt("+216" + from).getContent().contains("score : 3/4"), lastMt("+216" + from).getContent());
        // une seule partie par numéro
        mo(UUID.randomUUID().toString(), from, "QUIZGO encore");   // texte différent : sinon le dédoublonnage (30 s) l'ignore
        assertTrue(lastMt("+216" + from).getContent().contains("déjà participé"));
        // classement dans les résultats de la campagne
        mvc.perform(get("/admin/services/" + q.getId() + "/campaign").with(httpBasic("admin", PW))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].content").value("Parties terminées")).andExpect(jsonPath("$.results[0].count").value(1))
                .andExpect(jsonPath("$.results[2].content").value(org.hamcrest.Matchers.startsWith("#1 +216")));
        // réponse arabe sans diacritiques sur une autre partie
        String ar = msisdn();
        mo(UUID.randomUUID().toString(), ar, "QUIZGO");
        mo(UUID.randomUUID().toString(), ar, "تُونْس");
        assertTrue(lastMt("+216" + ar).getContent().contains("إجابة صحيحة") && lastMt("+216" + ar).getContent().contains("2/3: 2+2"), lastMt("+216" + ar).getContent()); // réponse arabe sans diacritiques, retour en arabe
    }

    @Test
    void premiumContentIsDeliveredByLimitedTokenLink() throws Exception {
        var c = typed("Premium", ServiceType.PREMIUM_CONTENT, "CODEIT");
        var item = new ContentItem();
        item.setService(c); item.setCode("GUIDE"); item.setTitle("Guide <b>VIP</b>"); item.setBody("Contenu secret"); item.setUrl("https://exemple.tn/guide"); item.setMaxUses(2); item.setTtlHours(1);
        contentItems.save(item);
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "CODEIT");   // un seul contenu : choisi automatiquement
        var m = lastMt("+216" + from);
        assertTrue(m.isBillable());
        var matcher = java.util.regex.Pattern.compile("/c/([A-Za-z0-9_-]{20,})").matcher(m.getContent());
        assertTrue(matcher.find(), m.getContent());
        String token = matcher.group(1);
        // 2 ouvertures autorisées (JSON puis HTML échappé), la 3e est refusée
        mvc.perform(get("/c/" + token)).andExpect(status().isOk()).andExpect(jsonPath("$.body").value("Contenu secret"));
        var html = mvc.perform(get("/c/" + token).header("Accept", "text/html")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Guide &lt;b&gt;VIP&lt;/b&gt;") && !html.contains("<b>VIP"), html);
        mvc.perform(get("/c/" + token)).andExpect(status().isGone());
        mvc.perform(get("/c/inconnu-token-0000000000")).andExpect(status().isNotFound());
        // lien expiré
        var tk = contentTokens.findByToken(token).orElseThrow();
        // API partenaire : émission d'un lien
        var partner = partners.save(partner("PremiumP"));
        c.setPartner(partner);
        services.save(c);
        String key = "vas_prem_" + sn;
        var cl = new ApiClient();
        cl.setName("prem"); cl.setPartner(partner); cl.setScopes("messages:send"); cl.setKeyHash(ApiKeyFilter.sha256(key));
        apiClients.save(cl);
        mvc.perform(post("/api/v1/content/" + c.getId() + "/links").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"msisdn\":\"98777666\",\"code\":\"GUIDE\"}")).andExpect(status().isAccepted());
        assertTrue(lastMt("+21698777666").getContent().contains("/c/"));
        mvc.perform(post("/api/v1/content/" + c.getId() + "/links").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"msisdn\":\"98777666\",\"code\":\"INCONNU\"}")).andExpect(status().isUnprocessableEntity());
        tk.setExpiresAt(Instant.now().minusSeconds(5));
        contentTokens.save(tk);
        mvc.perform(get("/c/" + token)).andExpect(status().isGone());
    }

    @Autowired ContentTokenRepo contentTokens;

    @Test
    void listsArePaginatedWithTotalCountHeader() throws Exception {
        for (int i = 0; i < 4; i++) mo(UUID.randomUUID().toString(), msisdn(), "VOTE P" + i);
        var admin = httpBasic("admin", PW);
        var page = mvc.perform(get("/admin/messages").param("size", "2").param("page", "0").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(header().exists("X-Total-Count")).andReturn();
        long total = Long.parseLong(page.getResponse().getHeader("X-Total-Count"));
        assertTrue(total >= 4);
        var p1 = mvc.perform(get("/admin/messages").param("size", "2").param("page", "1").with(admin)).andReturn().getResponse().getContentAsString();
        var p0 = page.getResponse().getContentAsString();
        assertNotEquals(p0, p1, "page suivante différente");
        mvc.perform(get("/admin/messages").param("size", "100000").with(admin)).andExpect(status().isOk()); // taille plafonnée à 500
        mvc.perform(get("/admin/ledger").param("size", "1").param("status", "PENDING").with(httpBasic("finance", PW))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(header().exists("X-Total-Count"));
        mvc.perform(get("/admin/audit").param("size", "3").with(admin)).andExpect(status().isOk()).andExpect(header().exists("X-Total-Count"));
        mvc.perform(get("/admin/services").param("size", "1").with(admin)).andExpect(jsonPath("$.length()").value(1)).andExpect(header().exists("X-Total-Count"));
        // filtre de période sur le ledger
        mvc.perform(get("/admin/ledger").param("from", Instant.now().plusSeconds(3600).toString()).with(httpBasic("finance", PW))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void jasminSubmitAckAndTransientErrorsDoNotCorruptState() throws Exception {
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "VOTE E");
        var m = lastMt("+216" + from);
        // ESME_ROK puis erreur transitoire : l'état reste SUBMITTED ; la livraison arrive ensuite normalement
        for (String st : new String[]{"ESME_ROK", "ESME_RTHROTTLED"}) {
            mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", m.getCorrelationId()).param("message_status", st)).andExpect(status().isOk());
            assertEquals(MtStatus.SUBMITTED, mts.findByCorrelationId(m.getCorrelationId()).orElseThrow().getStatus(), st);
        }
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", m.getCorrelationId()).param("message_status", "DELIVRD")).andExpect(status().isOk());
        assertEquals(MtStatus.DELIVERED, mts.findByCorrelationId(m.getCorrelationId()).orElseThrow().getStatus());
    }

    @Test
    void submittedMtWithoutDlrBecomesUnknownAndBillingDisputed() throws Exception {
        String from = msisdn();
        mo(UUID.randomUUID().toString(), from, "VOTE D");
        var m = lastMt("+216" + from);
        assertEquals(MtStatus.SUBMITTED, m.getStatus());
        // vieillit le MT au-delà du délai de DLR
        m = mts.findByCorrelationId(m.getCorrelationId()).orElseThrow();
        m.setUpdatedAt(Instant.now().minus(java.time.Duration.ofHours(100)));
        mts.save(m);
        assertTrue(mtSweeper.expireMissingDlr() >= 1);
        m = mts.findByCorrelationId(m.getCorrelationId()).orElseThrow();
        assertEquals(MtStatus.UNKNOWN, m.getStatus());
        assertEquals("DLR_TIMEOUT", m.getRawStatus());
        assertEquals(BillingStatus.DISPUTED, ledger.findByEventId("MT-" + m.getCorrelationId()).orElseThrow().getBillingStatus());
        // un DLR tardif fait foi : il lève la contestation (DISPUTED -> CHARGED)
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", m.getCorrelationId()).param("message_status", "DELIVRD")).andExpect(status().isOk());
        assertEquals(MtStatus.DELIVERED, mts.findByCorrelationId(m.getCorrelationId()).orElseThrow().getStatus());
        assertEquals(BillingStatus.CHARGED, ledger.findByEventId("MT-" + m.getCorrelationId()).orElseThrow().getBillingStatus());
    }

    @Test
    void tokenLoginWithMfa() throws Exception {
        user("tokuser", "SUPPORT", null);
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        // sans MFA : mot de passe seul → jeton Bearer utilisable
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"tokuser\",\"password\":\"bad-password-1\"}"))
                .andExpect(status().isUnauthorized());
        String t1 = om.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"tokuser\",\"password\":\"" + PW + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        mvc.perform(get("/admin/me").header("Authorization", "Bearer " + t1)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("tokuser"));
        mvc.perform(get("/admin/me").header("Authorization", "Bearer " + t1 + "x")).andExpect(status().isUnauthorized());
        // enrôlement MFA avec le jeton
        mvc.perform(post("/admin/me/mfa/setup").header("Authorization", "Bearer " + t1)).andExpect(status().isOk());
        String secret = userRepo.findByUsername("tokuser").orElseThrow().getTotpSecret();
        String c1 = tn.vas.security.Totp.generate(secret, System.currentTimeMillis() / 30000);
        mvc.perform(post("/admin/me/mfa/confirm").header("Authorization", "Bearer " + t1).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + c1 + "\"}")).andExpect(status().isOk());
        // l'ancien jeton (émis sans MFA) ne suffit plus
        mvc.perform(get("/admin/operators").header("Authorization", "Bearer " + t1)).andExpect(status().isUnauthorized());
        // nouvelle connexion : code exigé, puis jeton valide sans code à chaque requête
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"tokuser\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("X-MFA-Required", "true"));
        String c2 = tn.vas.security.Totp.generate(secret, System.currentTimeMillis() / 30000);
        String t2 = om.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"tokuser\",\"password\":\"" + PW + "\",\"code\":\"" + c2 + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        mvc.perform(get("/admin/operators").header("Authorization", "Bearer " + t2)).andExpect(status().isOk());
    }

    @Test
    void accountLocksAfterFiveFailures() throws Exception {
        user("locky", "SUPPORT", null);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"locky\",\"password\":\"nope-nope-nope1\"}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"locky\",\"password\":\"" + PW + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void dlrWebhookIsQueuedAndSigned() throws Exception {
        var p = partner("DlrHook");
        p.setWebhookUrl("http://localhost:9/dlr");
        p.setWebhookSecret("k");
        p = partners.save(p);
        var s = newService("DH " + sn, ServiceType.ALERT, ConsentMode.SIMPLE_OPT_IN, p);
        String key = "vas_dlrkey_" + sn;
        var c = new ApiClient();
        c.setName("dlr"); c.setPartner(p); c.setScopes("messages:send"); c.setKeyHash(ApiKeyFilter.sha256(key));
        apiClients.save(c);
        var res = mvc.perform(post("/api/v1/messages").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"98123456\",\"text\":\"x\",\"serviceId\":" + s.getId() + "}")).andExpect(status().isAccepted()).andReturn();
        String cid = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", cid).param("message_status", "DELIVRD")).andExpect(status().isOk());
        var hook = webhooks.findAll().stream().filter(w -> w.getEventId().equals("DLR-" + cid + "-DELIVERED")).findFirst().orElseThrow();
        assertEquals("PENDING", hook.getStatus());
        assertTrue(hook.getPayload().contains("\"status\":\"DELIVERED\""));
        // le rejeu du même DLR ne crée pas de second callback
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", cid).param("message_status", "DELIVRD")).andExpect(status().isOk());
        assertEquals(1, webhooks.findAll().stream().filter(w -> w.getEventId().startsWith("DLR-" + cid)).count());
    }
}
