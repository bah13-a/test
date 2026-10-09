package tn.vas;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;
import tn.vas.service.WebhookService;

/** Profil dev complet : démarre sans dépendance, charge les données de démonstration, DLR automatiques, comptes de chaque rôle. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevProfileTests {
    @Autowired MockMvc mvc;
    @Autowired OperatorRepo operators;
    @Autowired ServiceRepo services;
    @Autowired MtRepo mts;
    @Autowired LedgerRepo ledger;
    @Autowired UserRepo users;

    static final String PW = "Dev-pass-12345";

    @Test
    void seededDemoDataIsPresent() throws Exception {
        assertEquals(3, operators.count());
        assertTrue(services.findAll().stream().anyMatch(s -> s.getName().startsWith("Vote")));
        assertTrue(services.findAll().stream().anyMatch(s -> s.isRegulated() && !s.isRegulatoryApproved()), "un service réglementé non approuvé est fourni");
        for (String u : new String[]{"admin", "manager", "noc", "finance", "finance2", "support", "auditor", "club"}) assertTrue(users.findByUsername(u).isPresent(), u);
        mvc.perform(get("/auth/env")).andExpect(status().isOk()).andExpect(jsonPath("$.profile").value("dev"));
    }

    @Test
    void demoTrafficProducesAutomaticDlrsAndBilling() {
        // le trafic de démonstration passe par le simulateur qui renvoie des DLR (MIXED) : des MT atteignent un statut final et le ledger se remplit
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertTrue(mts.findAll().stream().anyMatch(m -> m.getStatus() == MtStatus.DELIVERED), "au moins un MT livré");
            assertTrue(ledger.findAll().stream().anyMatch(e -> e.getBillingStatus() == BillingStatus.CHARGED), "au moins un événement facturé");
        });
    }

    @Test
    void everyRoleCanLogInAndSeesItsScope() throws Exception {
        mvc.perform(get("/admin/dashboard").with(httpBasic("noc", PW))).andExpect(status().isOk());
        mvc.perform(get("/admin/ledger").with(httpBasic("noc", PW))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/ledger").with(httpBasic("finance", PW))).andExpect(status().isOk());
        mvc.perform(get("/portal/summary").with(httpBasic("club", PW))).andExpect(status().isOk()).andExpect(jsonPath("$.partner").value("Club Sportif Démo"));
        mvc.perform(get("/admin/users").with(httpBasic("admin", "Admin-dev-pass1"))).andExpect(status().isOk());
    }

    @Test
    void demoApiKeyWorks() throws Exception {
        mvc.perform(get("/api/v1/services").header("X-API-Key", "vas_dev_key_for_local_demo_only")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)); // les 2 services du partenaire démo
        mvc.perform(post("/api/v1/messages").header("X-API-Key", "vas_dev_key_for_local_demo_only").contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"98123456\",\"text\":\"test dev\",\"serviceId\":" + services.findAll().get(0).getId() + "}")).andExpect(status().isAccepted());
    }

    @Test
    void webhookSinkChecksSignatureAndFreshness() throws Exception {
        String body = "{\"event_id\":\"X\"}";
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = WebhookService.sign("dev-webhook-secret", ts + "." + body);
        mvc.perform(post("/dev/webhook-sink").header("X-VAS-Signature", sig).header("X-VAS-Timestamp", ts).header("X-VAS-Event-Id", "X")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/dev/webhook-sink").header("X-VAS-Signature", "bad").header("X-VAS-Timestamp", ts).header("X-VAS-Event-Id", "Y")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        String old = String.valueOf(System.currentTimeMillis() / 1000 - 3600);
        mvc.perform(post("/dev/webhook-sink").header("X-VAS-Signature", WebhookService.sign("dev-webhook-secret", old + "." + body)).header("X-VAS-Timestamp", old)
                .header("X-VAS-Event-Id", "Z").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }

    @Test
    void mockStatementEnablesReconciliationDemo() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> ledger.findAll().stream().anyMatch(e -> e.getBillingStatus() == BillingStatus.CHARGED && e.getOperator().getCode().equals("TT")));
        String csv = mvc.perform(get("/admin/sim/statement").param("operator", "TT").with(httpBasic("admin", "Admin-dev-pass1"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(csv.startsWith("event_id;amount;status") && csv.contains("MT-inconnu-chez-nous"));
    }
}
