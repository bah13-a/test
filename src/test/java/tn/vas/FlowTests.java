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

    Operator tt;
    ShortCode sc;
    VasService vote;
    VasService abo;
    String sn;           // préfixe unique par test (isolation sans nettoyage de base)

    @BeforeEach
    void setup() {
        sn = String.valueOf(10000 + (int) (Math.random() * 89999));
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
        mvc.perform(get("/admin/audit").with(httpBasic("finance", "finance"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/audit").with(httpBasic("admin", "admin"))).andExpect(status().isOk());

        String json = "{\"serviceId\":" + vote.getId() + ",\"eventType\":\"MT\",\"grossAmount\":0.5,\"operatorPercent\":40,\"taxPercent\":19}";
        var res = mvc.perform(post("/admin/tariffs").with(httpBasic("finance", "finance"))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk()).andReturn();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/admin/tariffs/" + id + "/approve").with(httpBasic("finance", "finance"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/tariffs/" + id + "/approve").with(httpBasic("finance2", "finance2"))).andExpect(status().isOk());
    }

    Partner partner(String n) {
        var p = new Partner();
        p.setName(n);
        return p;
    }
}
