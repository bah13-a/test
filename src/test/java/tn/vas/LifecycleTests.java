package tn.vas;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;
import tn.vas.security.*;
import tn.vas.service.*;

/** Points 10 à 15 : renouvellement, TPS, envoi programmé, facturation, OAuth2/SSRF, comptes, chiffrement. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LifecycleTests {
    static final String PW = "Passw0rd-long-1";
    static final AtomicInteger SN = new AtomicInteger(60000 + (int) (Math.random() * 3000));
    static final ObjectMapper OM = new ObjectMapper();
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
    @Autowired SubscriptionService subscriptionService;
    @Autowired MtSweeper sweeper;
    @Autowired UserService userService;
    @Autowired UserRepo userRepo;
    @Autowired BillingPeriodRepo periods;
    @Autowired PayoutRepo payouts;
    @Autowired JdbcTemplate jdbc;
    @Autowired tn.vas.query.ReportingQueries reporting;
    @Autowired tn.vas.projection.Projections projections;
    @Autowired MoRepo mos;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    Operator tt;
    ShortCode sc;
    VasService abo;
    Partner partner;
    String sn;

    void user(String name, String role, Partner p) {
        if (userRepo.findByUsername(name).isEmpty()) userService.create(name, PW, List.of(role), p);
    }

    @BeforeEach
    void setup() {
        user("fin_a", "FINANCE", null);
        user("fin_b", "FINANCE", null);
        user("root", "SUPER_ADMIN", null);
        sn = String.valueOf(SN.incrementAndGet());
        tt = operators.findByCode("TT").orElseThrow();
        sc = new ShortCode();
        sc.setNumber(sn);
        sc.setOperator(tt);
        sc = shortCodes.save(sc);
        partner = new Partner();
        partner.setName("Part " + sn);
        partner.setSharePercent(new BigDecimal("50"));
        partner = partners.save(partner);
        abo = service("Abo " + sn, ServiceType.SUBSCRIPTION, partner);
        keyword(abo, "ABO");
        tariff(abo, EventType.SUBSCRIPTION, "2.000");
        tariff(abo, EventType.RENEWAL, "2.000");
    }

    VasService service(String name, ServiceType type, Partner p) {
        var s = new VasService();
        s.setName(name); s.setType(type); s.setShortCode(sc); s.setStatus(ServiceStatus.ACTIVE);
        s.setConsentMode(ConsentMode.SIMPLE_OPT_IN); s.setPartner(p); s.setReplyOk("Merci");
        return services.save(s);
    }

    void keyword(VasService s, String w) {
        var k = new Keyword();
        k.setService(s); k.setWord(w);
        keywords.save(k);
    }

    void tariff(VasService s, EventType e, String amount) {
        var t = new Tariff();
        t.setService(s); t.setEventType(e); t.setGrossAmount(new BigDecimal(amount));
        t.setOperatorPercent(new BigDecimal("40")); t.setTaxPercent(new BigDecimal("19"));
        t.setEffectiveFrom(Instant.now().minusSeconds(3600)); t.setApproved(true); t.setCreatedBy("test");
        tariffs.save(t);
    }

    String msisdn() { return "9" + String.format("%07d", (int) (Math.random() * 9999999)); }

    void mo(String from, String content) throws Exception {
        mvc.perform(post("/callbacks/mo").param("secret", "test-secret").param("id", UUID.randomUUID().toString()).param("from", from)
                .param("to", sn).param("content", content).param("origin-connector", "smppc_tt")).andExpect(status().isOk());
    }

    void dlr(MtMessage m, String status) throws Exception {
        mvc.perform(post("/callbacks/dlr").param("secret", "test-secret").param("cid", m.getCorrelationId()).param("message_status", status)).andExpect(status().isOk());
    }

    MtMessage renewalMt(String e164) {
        return mts.findTop100ByMsisdnOrderByCreatedAtDesc(e164).stream().filter(x -> x.getSubscriptionId() != null).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ point 10 : renouvellements
    @Test
    void renewalFailureRetriesNextDayThenSuspendsAfterThreeFailures() throws Exception {
        String from = msisdn();
        mo(from, "ABO");
        String e164 = "+216" + from;
        for (int i = 1; i <= 3; i++) {
            var s = subs.findByMsisdnAndService(e164, abo).orElseThrow();
            s.setNextRenewalAt(Instant.now().minusSeconds(5));
            subs.save(s);
            subscriptionService.renewDue();
            var m = renewalMt(e164);
            dlr(m, "UNDELIV");
            s = subs.findByMsisdnAndService(e164, abo).orElseThrow();
            assertEquals(i, s.getRenewalFailures());
            if (i < 3) {
                assertEquals(SubStatus.ACTIVE, s.getStatus());
                assertTrue(s.getNextRenewalAt().isBefore(Instant.now().plus(Duration.ofHours(25))), "nouvel essai sous ~1 jour");
            } else {
                assertEquals(SubStatus.SUSPENDED, s.getStatus());
                assertNull(s.getNextRenewalAt());
            }
        }
        // un abonnement suspendu n'est plus renouvelé
        int before = mts.findTop100ByMsisdnOrderByCreatedAtDesc(e164).size();
        subscriptionService.renewDue();
        assertEquals(before, mts.findTop100ByMsisdnOrderByCreatedAtDesc(e164).size());
    }

    @Test
    void renewalDeliveredResetsFailuresAndPushesNextRenewal() throws Exception {
        String from = msisdn();
        mo(from, "ABO");
        String e164 = "+216" + from;
        var s = subs.findByMsisdnAndService(e164, abo).orElseThrow();
        s.setRenewalFailures(2);
        s.setNextRenewalAt(Instant.now().minusSeconds(5));
        subs.save(s);
        subscriptionService.renewDue();
        dlr(renewalMt(e164), "DELIVRD");
        s = subs.findByMsisdnAndService(e164, abo).orElseThrow();
        assertEquals(0, s.getRenewalFailures());
        assertTrue(s.getNextRenewalAt().isAfter(Instant.now().plus(Duration.ofDays(29))));
    }

    // ------------------------------------------------------------------ point 11 : TPS
    @Test
    void rateGateSpacesCallsPerKey() throws Exception {
        var gate = new RateGate(1.0);
        long t0 = System.nanoTime();
        for (int i = 0; i < 6; i++) gate.acquire("k", 20); // 20 TPS => 50 ms entre deux envois
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 230, "6 envois à 20 TPS : au moins 250 ms attendus, mesuré " + ms);
        long t1 = System.nanoTime();
        for (int i = 0; i < 6; i++) gate.acquire("illimité", 0);
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 50);
    }

    @Test
    void serviceAndPartnerTpsAreEditableByManagers() throws Exception {
        user("mgr_a", "VAS_MANAGER", null);
        mvc.perform(patch("/admin/services/" + abo.getId()).with(httpBasic("mgr_a", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"maxTps\":7}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/admin/partners/" + partner.getId()).with(httpBasic("mgr_a", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"maxTps\":3}"))
                .andExpect(status().isOk());
        assertEquals(7, services.findById(abo.getId()).orElseThrow().getMaxTps());
        assertEquals(3, partners.findById(partner.getId()).orElseThrow().getMaxTps());
    }

    // ------------------------------------------------------------------ point 12 : envoi programmé
    @Test
    void scheduledMtIsHeldUntilDue() throws Exception {
        String key = "vas_sched_" + sn;
        var c = new ApiClient();
        c.setName("sched"); c.setPartner(partner); c.setScopes("messages:send,messages:read"); c.setKeyHash(ApiKeyFilter.sha256(key));
        apiClients.save(c);
        String at = Instant.now().plus(Duration.ofHours(2)).toString();
        String to = msisdn();
        String body = "{\"to\":\"" + to + "\",\"text\":\"Plus tard\",\"serviceId\":" + abo.getId() + ",\"scheduleAt\":\"" + at + "\"}";
        mvc.perform(post("/api/v1/messages").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        var m = mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + to).get(0);
        assertEquals(MtStatus.PENDING, m.getStatus());
        sweeper.sweep();
        assertEquals(MtStatus.PENDING, mts.findById(m.getId()).orElseThrow().getStatus(), "pas d'envoi avant l'échéance");
        m = mts.findById(m.getId()).orElseThrow();
        m.setScheduledAt(Instant.now().minusSeconds(5));
        m.setUpdatedAt(Instant.now().minusSeconds(60));
        mts.save(m);
        sweeper.sweep();
        assertEquals(MtStatus.SUBMITTED, mts.findById(m.getId()).orElseThrow().getStatus());
        // date trop lointaine refusée
        mvc.perform(post("/api/v1/messages").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace(at, Instant.now().plus(Duration.ofDays(90)).toString()))).andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ point 13 : facturation
    LedgerEvent event(String id, BillingStatus st, Instant created, String partnerShare) {
        var e = new LedgerEvent();
        e.setEventId(id + sn); e.setEventType(EventType.MT); e.setOperator(tt); e.setService(abo); e.setShortCode(sn); e.setMsisdn("+216" + msisdn());
        e.setGrossAmount(new BigDecimal("2.000")); e.setTaxes(new BigDecimal("0.319")); e.setOperatorShare(new BigDecimal("0.672"));
        e.setPartnerShare(new BigDecimal(partnerShare)); e.setProviderShare(new BigDecimal("0.500")); e.setBillingStatus(st);
        e.setCreatedAt(created); e.setUpdatedAt(created);
        return ledger.save(e);
    }

    String auth(String user) throws Exception {
        return "Bearer " + OM.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"" + PW + "\"}")).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void billingPeriodAdjustmentsStatementsAndPayouts() throws Exception {
        // fenêtre isolée dans le passé lointain (année 2001+n) : n'interfère pas avec les autres tests
        int year = 2001 + SN.get() % 15000 % 20;
        Instant from = Instant.parse(year + "-03-01T00:00:00Z"), to = Instant.parse(year + "-04-01T00:00:00Z");
        if (periods.overlaps(from, to)) { from = Instant.parse((year + 30) + "-03-01T00:00:00Z"); to = Instant.parse((year + 30) + "-04-01T00:00:00Z"); }
        Instant mid = from.plus(Duration.ofDays(5));
        var charged = event("C1-", BillingStatus.CHARGED, mid, "0.500");
        var charged2 = event("C2-", BillingStatus.CHARGED, mid.plusSeconds(1), "0.500");
        var pending = event("P1-", BillingStatus.PENDING, mid.plusSeconds(2), "0.500");
        String f = from.toString(), t = to.toString();
        var fa = httpBasic("fin_a", PW);

        // clôture refusée tant qu'un événement est PENDING
        mvc.perform(post("/admin/billing/periods").with(fa).contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"" + f + "\",\"to\":\"" + t + "\"}"))
                .andExpect(status().isConflict());
        // remboursement en période ouverte : original REVERSED
        mvc.perform(post("/admin/billing/adjustments").with(fa).contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"" + pending.getEventId() + "\",\"reason\":\"doublon\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("REVERSED"));
        assertEquals(BillingStatus.REVERSED, ledger.findByEventId(pending.getEventId()).orElseThrow().getBillingStatus());
        // motif obligatoire
        mvc.perform(post("/admin/billing/adjustments").with(fa).contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"" + charged.getEventId() + "\",\"reason\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
        // relevé : 2 événements CHARGED
        mvc.perform(get("/admin/billing/statements").with(fa).param("partnerId", partner.getId().toString()).param("from", f).param("to", t))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.service=='TOTAL')].partnerShare").value(1.0));
        mvc.perform(get("/admin/billing/statements").with(fa).param("partnerId", partner.getId().toString()).param("from", f).param("to", t).param("format", "xlsx"))
                .andExpect(status().isOk());
        // clôture OK, puis chevauchement refusé
        mvc.perform(post("/admin/billing/periods").with(fa).contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"" + f + "\",\"to\":\"" + t + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events").value(2));
        mvc.perform(post("/admin/billing/periods").with(fa).contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"" + f + "\",\"to\":\"" + t + "\"}"))
                .andExpect(status().isConflict());
        // période figée : transition ignorée
        assertEquals(BillingStatus.CHARGED, ledger.findByEventId(charged.getEventId()).orElseThrow().getBillingStatus());
        // remboursement en période clôturée : ajustement négatif, original intact, idempotent
        mvc.perform(post("/admin/billing/adjustments").with(fa).contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"" + charged2.getEventId() + "\",\"reason\":\"réclamation\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("ADJUSTMENT"));
        var adj = ledger.findByEventId("ADJ-" + charged2.getEventId()).orElseThrow();
        assertEquals(0, new BigDecimal("-0.500").compareTo(adj.getPartnerShare()));
        assertEquals(EventType.ADJUSTMENT, adj.getEventType());
        assertEquals(BillingStatus.CHARGED, ledger.findByEventId(charged2.getEventId()).orElseThrow().getBillingStatus());
        mvc.perform(post("/admin/billing/adjustments").with(fa).contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"" + charged2.getEventId() + "\",\"reason\":\"encore\"}"))
                .andExpect(status().isConflict());

        // reversement : créé par fin_a, payé par un autre (4 yeux), pas de chevauchement
        String pbody = "{\"partnerId\":" + partner.getId() + ",\"from\":\"" + f + "\",\"to\":\"" + t + "\"}";
        String created = mvc.perform(post("/admin/billing/payouts").with(fa).contentType(MediaType.APPLICATION_JSON).content(pbody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(1.0)).andReturn().getResponse().getContentAsString();
        long pid = OM.readTree(created).get("id").asLong();
        mvc.perform(post("/admin/billing/payouts").with(fa).contentType(MediaType.APPLICATION_JSON).content(pbody)).andExpect(status().isConflict());
        mvc.perform(post("/admin/billing/payouts/" + pid + "/pay").with(fa).contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"VIR-1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/billing/payouts/" + pid + "/pay").with(httpBasic("fin_b", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"VIR-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(post("/admin/billing/payouts/" + pid + "/pay").with(httpBasic("fin_b", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"VIR-2\"}"))
                .andExpect(status().isConflict());

        // portail partenaire : ses relevés et reversements seulement
        user("part_" + sn, "PARTNER", partner);
        var pu = httpBasic("part_" + sn, PW);
        mvc.perform(get("/portal/statements").with(pu).param("from", f).param("to", t)).andExpect(status().isOk());
        mvc.perform(get("/portal/payouts").with(pu)).andExpect(status().isOk()).andExpect(jsonPath("$[0].reference").value("VIR-1"));
        // un autre partenaire ne voit pas ce reversement
        var other = new Partner(); other.setName("Autre " + sn); other.setSharePercent(BigDecimal.ONE); other = partners.save(other);
        user("other_" + sn, "PARTNER", other);
        mvc.perform(get("/portal/payouts").with(httpBasic("other_" + sn, PW))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // rôle non financier : refusé
        user("sup_a", "SUPPORT", null);
        mvc.perform(post("/admin/billing/periods").with(httpBasic("sup_a", PW)).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ point 14 : OAuth2 et SSRF
    @Test
    void oauthClientCredentialsGivesBearerTokenForTheApi() throws Exception {
        String key = "vas_oauth_" + sn;
        var c = new ApiClient();
        c.setName("oauth"); c.setPartner(partner); c.setScopes("services:read"); c.setKeyHash(ApiKeyFilter.sha256(key));
        c = apiClients.save(c);
        String cid = "client-" + c.getId();
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials").param("client_id", cid).param("client_secret", "mauvaise"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "password").param("client_id", cid).param("client_secret", key))
                .andExpect(status().isBadRequest());
        // secret valide mais client_id d'un autre client : refusé
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials").param("client_id", "client-1").param("client_secret", key))
                .andExpect(status().isUnauthorized());
        String token = OM.readTree(mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials")
                .param("client_id", cid).param("client_secret", key)).andExpect(status().isOk()).andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn().getResponse().getContentAsString()).get("access_token").asText();
        mvc.perform(get("/api/v1/services").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/services").header("Authorization", "Bearer " + token + "x")).andExpect(status().isUnauthorized());
        // un jeton de session admin n'ouvre pas l'API partenaires
        mvc.perform(get("/api/v1/services").header("Authorization", auth("root"))).andExpect(status().isUnauthorized());
        // client révoqué : le jeton cesse de fonctionner
        c.setActive(false);
        apiClients.save(c);
        mvc.perform(get("/api/v1/services").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void urlGuardRejectsUnsafeWebhookTargets() {
        var strict = new UrlGuard(false, false, "");
        for (String bad : List.of("http://example.com/hook", "https://localhost/hook", "https://127.0.0.1/x", "https://10.1.2.3/x", "https://192.168.1.5/x",
                "https://169.254.169.254/latest/meta-data", "https://[::1]/x", "https://100.64.0.1/x", "https://user:pw@example.com/x", "ftp://example.com/x", "pas une url"))
            assertThrows(IllegalArgumentException.class, () -> strict.check(bad), bad);
        assertDoesNotThrow(() -> new UrlGuard(true, true, "").check("http://localhost:9999/hook"));
        var listed = new UrlGuard(false, true, "hooks.partner.tn");
        assertDoesNotThrow(() -> listed.check("https://hooks.partner.tn/x"));
        assertThrows(IllegalArgumentException.class, () -> listed.check("https://evil.tn/x"));
    }

    @Test
    void partnerWebhookUrlIsValidatedByTheApi() throws Exception {
        user("mgr_b", "VAS_MANAGER", null);
        mvc.perform(patch("/admin/partners/" + partner.getId()).with(httpBasic("mgr_b", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"webhookUrl\":\"ftp://x\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ point 15 : comptes et sessions
    @Test
    void newUserMustChangePasswordAndOldSessionsAreRevoked() throws Exception {
        String name = "newbie_" + sn;
        mvc.perform(post("/admin/users").with(httpBasic("root", PW)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"" + PW + "\",\"roles\":[\"SUPPORT\"]}")).andExpect(status().isOk());
        String t1 = auth(name);
        mvc.perform(get("/admin/me").header("Authorization", t1)).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/admin/messages").header("Authorization", t1)).andExpect(status().isForbidden()).andExpect(header().string("X-Password-Change-Required", "true"));
        mvc.perform(get("/admin/messages").with(httpBasic(name, PW))).andExpect(status().isForbidden()); // Basic soumis à la même règle
        String bodyBad = "{\"current\":\"faux-faux-faux1\",\"newPassword\":\"Nouveau-mdp-2026\"}";
        mvc.perform(post("/admin/me/password").header("Authorization", t1).contentType(MediaType.APPLICATION_JSON).content(bodyBad)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/me/password").header("Authorization", t1).contentType(MediaType.APPLICATION_JSON).content("{\"current\":\"" + PW + "\",\"newPassword\":\"court1\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/me/password").header("Authorization", t1).contentType(MediaType.APPLICATION_JSON).content("{\"current\":\"" + PW + "\",\"newPassword\":\"" + PW + "\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/me/password").header("Authorization", t1).contentType(MediaType.APPLICATION_JSON).content("{\"current\":\"" + PW + "\",\"newPassword\":\"Nouveau-mdp-2026\"}")).andExpect(status().isOk());
        // l'ancienne session est révoquée
        mvc.perform(get("/admin/me").header("Authorization", t1)).andExpect(status().isUnauthorized());
        String t2 = OM.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"Nouveau-mdp-2026\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        mvc.perform(get("/admin/messages").header("Authorization", "Bearer " + t2)).andExpect(status().isOk());
        // déconnexion globale
        mvc.perform(post("/admin/me/logout-all").header("Authorization", "Bearer " + t2)).andExpect(status().isOk());
        mvc.perform(get("/admin/me").header("Authorization", "Bearer " + t2)).andExpect(status().isUnauthorized());
        // l'administrateur modifie les rôles : sessions révoquées, nouveau mot de passe imposé au reset
        String t3 = OM.readTree(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"Nouveau-mdp-2026\"}")).andReturn().getResponse().getContentAsString()).get("token").asText();
        long id = userRepo.findByUsername(name).orElseThrow().getId();
        mvc.perform(patch("/admin/users/" + id).with(httpBasic("root", PW)).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Reset-admin-2026\"}")).andExpect(status().isOk());
        mvc.perform(get("/admin/me").header("Authorization", "Bearer " + t3)).andExpect(status().isUnauthorized());
        assertTrue(userRepo.findByUsername(name).orElseThrow().isMustChangePassword());
    }

    @Test
    void msisdnsAreEncryptedDeterministicallyAtRest() {
        MsisdnCrypto.configure("test-data-key-0123456789abcdef0123456789abcdef"); // clé statique : un autre contexte Spring a pu la changer
        String clear = "+216" + msisdn();
        String enc = MsisdnCrypto.encrypt(clear);
        assertTrue(enc.startsWith("enc:v1:"));
        assertFalse(enc.contains(clear.substring(4)));
        assertEquals(enc, MsisdnCrypto.encrypt(clear), "déterministe : la recherche par égalité reste possible");
        assertEquals(clear, MsisdnCrypto.decrypt(enc));
        assertNotEquals(enc, MsisdnCrypto.encrypt(clear.substring(0, clear.length() - 1) + "0".charAt(0) + ""), "numéros différents, valeurs différentes");
        // altération détectée
        char[] ch = enc.toCharArray();
        ch[ch.length - 3] = ch[ch.length - 3] == 'A' ? 'B' : 'A';
        assertThrows(IllegalStateException.class, () -> MsisdnCrypto.decrypt(new String(ch)));
    }

    @Test
    void databaseStoresEncryptedMsisdnButApiReturnsClearValues() throws Exception {
        String from = msisdn();
        mo(from, "ABO");
        List<String> raw = jdbc.queryForList("select msisdn from subscription where msisdn like ?", String.class, "%");
        var mine = subs.findByMsisdnAndService("+216" + from, abo).orElseThrow();
        assertEquals("+216" + from, mine.getMsisdn());
        String stored = jdbc.queryForObject("select msisdn from subscription where id = ?", String.class, mine.getId());
        assertTrue(stored.startsWith("enc:v1:"), "stocké chiffré : " + stored);
        assertFalse(raw.isEmpty());
        assertTrue(jdbc.queryForObject("select msisdn from mo_message where id = (select max(id) from mo_message)", String.class).startsWith("enc:v1:"));
    }

    // ------------------------------------------------------------------ points techniques : freinage des échecs d'authentification
    @Test
    void repeatedAuthenticationFailuresFromOneSourceAreThrottled() throws Exception {
        String key = "vas_throttle_" + sn;
        var c = new ApiClient();
        c.setName("thr"); c.setPartner(partner); c.setScopes("services:read"); c.setKeyHash(ApiKeyFilter.sha256(key));
        apiClients.save(c);
        var from = (org.springframework.test.web.servlet.request.RequestPostProcessor) r -> { r.setRemoteAddr("203.0.113." + (SN.get() % 250)); return r; };
        for (int i = 0; i < 20; i++) mvc.perform(get("/api/v1/services").header("X-API-Key", "mauvaise-" + i).with(from)).andExpect(status().isUnauthorized());
        // la source est maintenant freinée, même avec une clé valide
        mvc.perform(get("/api/v1/services").header("X-API-Key", key).with(from)).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        // une autre source n'est pas affectée
        mvc.perform(get("/api/v1/services").header("X-API-Key", key)).andExpect(status().isOk());
        // idem pour la connexion à l'interface
        var from2 = (org.springframework.test.web.servlet.request.RequestPostProcessor) r -> { r.setRemoteAddr("198.51.100." + (SN.get() % 250)); return r; };
        for (int i = 0; i < 20; i++) mvc.perform(post("/auth/login").with(from2).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"inconnu" + i + "\",\"password\":\"xxxxxxxxxxxx1\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").with(from2).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"root\",\"password\":\"" + PW + "\"}")).andExpect(status().isTooManyRequests());
    }

    // ------------------------------------------------------------------ routage : plages, portabilité, import
    @Test
    void routingUsesPortedNumberThenLongestPrefixAndAcceptsCsvImport() throws Exception {
        user("noc_a", "NOC", null);
        var noc = httpBasic("noc_a", PW);
        String prefix = sn; // 5 chiffres, unique par test
        String num = prefix + "123";
        var ranges = new org.springframework.mock.web.MockMultipartFile("file", "plages.csv", "text/csv", ("prefix;operator\n" + prefix.substring(0, 3) + ";OOREDOO\n" + prefix + ";TT\nabc;TT\n" + prefix + ";INCONNU\n").getBytes());
        mvc.perform(multipart("/admin/routing/import").file(ranges).param("kind", "ranges").with(noc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2)).andExpect(jsonPath("$.rejected").value(2));
        // plus long préfixe : le préfixe à 5 chiffres (TT) l'emporte sur celui à 3 chiffres (OOREDOO)
        mvc.perform(get("/admin/routing/resolve").param("msisdn", num).with(noc)).andExpect(jsonPath("$.operator").value("TT")).andExpect(jsonPath("$.routed").value(true));
        // numéro porté : exception exacte prioritaire
        var ported = new org.springframework.mock.web.MockMultipartFile("file", "ports.csv", "text/csv", (num + ";ORANGE\n").getBytes());
        mvc.perform(multipart("/admin/routing/import").file(ported).param("kind", "ported").with(noc)).andExpect(jsonPath("$.created").value(1));
        mvc.perform(get("/admin/routing/resolve").param("msisdn", num).with(noc)).andExpect(jsonPath("$.operator").value("ORANGE"));
        mvc.perform(get("/admin/routing/resolve").param("msisdn", prefix + "999").with(noc)).andExpect(jsonPath("$.operator").value("TT"));
        // numéro porté stocké chiffré
        assertTrue(jdbc.queryForObject("select msisdn from ported_number order by id desc limit 1", String.class).startsWith("enc:v1:"));
        // l'API route sans operator ni serviceId grâce aux plages
        String key = "vas_route_" + sn;
        var c = new ApiClient();
        c.setName("route"); c.setScopes("messages:send"); c.setKeyHash(ApiKeyFilter.sha256(key));
        apiClients.save(c);
        mvc.perform(post("/api/v1/messages").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"" + num + "\",\"text\":\"salut\",\"sender\":\"WEB\"}")).andExpect(status().isAccepted());
        assertEquals("ORANGE", mts.findTop100ByMsisdnOrderByCreatedAtDesc("+216" + num).get(0).getOperator().getCode());
        // rôle sans droit d'écriture
        mvc.perform(multipart("/admin/routing/import").file(ranges).param("kind", "ranges").with(httpBasic("fin_a", PW))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ CQRS : modèles de lecture
    private java.util.Map<String, java.util.Map<String, Long>> readModel() {
        return reporting.traffic("MO", new java.sql.Timestamp(0), abo.getId()).isEmpty() && reporting.traffic("MT", new java.sql.Timestamp(0), abo.getId()).isEmpty()
                ? java.util.Map.of("mo", java.util.Map.of(), "mt", java.util.Map.of())
                : java.util.Map.of("mo", reporting.traffic("MO", new java.sql.Timestamp(0), abo.getId()), "mt", reporting.traffic("MT", new java.sql.Timestamp(0), abo.getId()));
    }

    private java.util.Map<String, java.util.Map<String, Long>> writeModel() {
        java.util.Map<String, Long> mo = new java.util.TreeMap<>(), mt = new java.util.TreeMap<>();
        mos.countByOutcome(abo).forEach(o -> mo.put(o[0].toString(), (Long) o[1]));
        mts.countByStatus(abo).forEach(o -> mt.put(o[0].toString(), (Long) o[1]));
        return java.util.Map.of("mo", mo, "mt", mt);
    }

    @Test
    void readModelsFollowTheWriteModelThroughEvents() throws Exception {
        for (int i = 0; i < 3; i++) mo(msisdn(), "ABO");
        var first = mts.findAll().stream().filter(m -> m.getService() != null && m.getService().getId().equals(abo.getId())).findFirst().orElseThrow();
        dlr(first, "DELIVRD");
        assertEquals(writeModel(), readModel(), "la projection reflète exactement les tables d'écriture (MO par issue, MT par statut courant)");
        assertTrue(readModel().get("mt").getOrDefault("DELIVERED", 0L) >= 1);
        // facturation : mêmes totaux que le ledger
        var proj = (java.util.Map<?, ?>) reporting.billing(new java.sql.Timestamp(0), java.util.List.of(abo.getId()));
        for (Object[] o : ledger.totalsByStatus(java.util.List.of(abo))) {
            var row = (java.util.Map<?, ?>) proj.get(o[0].toString());
            assertNotNull(row, "statut " + o[0]);
            assertEquals(0, ((BigDecimal) o[1]).compareTo((BigDecimal) row.get("gross")));
            assertEquals(0, ((BigDecimal) o[2]).compareTo((BigDecimal) row.get("partner")));
            assertEquals(((Number) o[4]).longValue(), ((Number) row.get("events")).longValue());
        }
        // les contrôleurs de requête servent ces données
        user("aud_a", "AUDITOR", null);
        mvc.perform(get("/admin/dashboard").with(httpBasic("aud_a", PW))).andExpect(status().isOk()).andExpect(jsonPath("$.mo.ROUTED").exists());
        mvc.perform(get("/admin/services/" + abo.getId() + "/campaign").with(httpBasic("aud_a", PW))).andExpect(status().isOk())
                .andExpect(jsonPath("$.mt.DELIVERED").exists()).andExpect(jsonPath("$.billing").exists());
    }

    @Test
    void reconciliationHealsLostEventsAndProjectionFailuresNeverBreakCommands() throws Exception {
        mo(msisdn(), "ABO");
        assertEquals(writeModel(), readModel());
        // événements perdus (ex. arrêt entre commit et projection) : la réconciliation recalcule depuis les tables sources
        jdbc.update("delete from rm_traffic_hourly where service_id = ?", abo.getId());
        jdbc.update("delete from rm_ledger_hourly where service_id = ?", abo.getId());
        assertTrue(readModel().get("mt").isEmpty());
        projections.reconcile();
        assertEquals(writeModel(), readModel());
        // la table de lecture est indisponible : le MO est quand même traité (le côté commande n'attend pas la projection)
        double before = meters.counter("vas.projection.error", "event", "MoRecorded").count();
        jdbc.execute("alter table rm_traffic_hourly rename to rm_traffic_hourly_off");
        try {
            String from = msisdn();
            mo(from, "ABO");
            assertTrue(subs.findByMsisdnAndService("+216" + from, abo).isPresent(), "l'abonnement est créé malgré l'échec de projection");
        } finally {
            jdbc.execute("alter table rm_traffic_hourly_off rename to rm_traffic_hourly");
        }
        assertTrue(meters.counter("vas.projection.error", "event", "MoRecorded").count() > before, "l'échec est compté pour l'alerte");
        projections.reconcile();
        assertEquals(writeModel(), readModel(), "la réconciliation rattrape l'événement manqué");
    }
}
