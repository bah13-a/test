package tn.vas;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;
import tn.vas.security.UserService;
import tn.vas.service.StartupData;

/** Réglages venant du .env : format des relevés par opérateur, durée de session, données de démarrage (routage, catalogue). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "vas.session-ttl-minutes=7",
        "vas.recon.operators.OOREDOO.separator=;", "vas.recon.operators.OOREDOO.id-column=ref", "vas.recon.operators.OOREDOO.amount-column=montant",
        "vas.recon.operators.OOREDOO.status-column=etat", "vas.recon.operators.OOREDOO.status-map=PAYE=CHARGED;REFUSE=REJECTED", "vas.recon.operators.OOREDOO.amount-divisor=1000"})
class EnvDrivenTests {
    @Autowired MockMvc mvc;
    @Autowired OperatorRepo operators;
    @Autowired LedgerRepo ledger;
    @Autowired UserRepo userRepo;
    @Autowired UserService userService;
    @Autowired StartupData startup;
    @Autowired PartnerRepo partners;
    @Autowired ServiceRepo services;
    @Autowired KeywordRepo keywords;
    @Autowired TariffRepo tariffs;
    @Autowired ShortCodeRepo shortCodes;
    @Autowired tn.vas.service.RoutingService routing;
    static final String PW = "Passw0rd-long-1";

    void user(String name, String role) {
        if (userRepo.findByUsername(name).isEmpty()) userService.create(name, PW, List.of(role), null);
    }

    @Test
    void operatorStatementFormatComesFromTheEnvironment() throws Exception {
        user("fin_env", "FINANCE");
        var op = operators.findByCode("OOREDOO").orElseThrow();
        String id = "ENV-" + System.nanoTime();
        var e = new LedgerEvent();
        e.setEventId(id); e.setEventType(EventType.MT); e.setOperator(op); e.setMsisdn("+21698000001");
        e.setGrossAmount(new BigDecimal("1.500")); e.setTaxes(BigDecimal.ZERO); e.setOperatorShare(BigDecimal.ZERO); e.setPartnerShare(BigDecimal.ZERO); e.setProviderShare(BigDecimal.ZERO);
        e.setBillingStatus(BillingStatus.CHARGED); e.setCreatedAt(Instant.now()); e.setUpdatedAt(Instant.now());
        ledger.save(e);
        // relevé « à la façon de l'opérateur » : séparateur ;, colonnes ref/montant/etat, montants en millimes, statut PAYE
        var csv = new MockMultipartFile("file", "releve.csv", "text/csv", ("ref;montant;etat\n" + id + ";1500;PAYE\n").getBytes());
        mvc.perform(multipart("/admin/reconciliation/OOREDOO").file(csv).param("from", Instant.now().minusSeconds(3600).toString()).param("to", Instant.now().plusSeconds(3600).toString())
                .with(httpBasic("fin_env", PW))).andExpect(status().isOk()).andExpect(jsonPath("$.summary.MATCHED").value(1));
        // les paramètres de la requête restent prioritaires sur le .env
        var plain = new MockMultipartFile("file", "releve.csv", "text/csv", ("id,amt,st\n" + id + ",1.500,CHARGED\n").getBytes());
        mvc.perform(multipart("/admin/reconciliation/OOREDOO").file(plain).param("from", Instant.now().minusSeconds(3600).toString()).param("to", Instant.now().plusSeconds(3600).toString())
                .param("separator", ",").param("idColumn", "id").param("amountColumn", "amt").param("statusColumn", "st").with(httpBasic("fin_env", PW)))
                .andExpect(status().isOk());
    }

    @Test
    void sessionLifetimeComesFromTheEnvironment() throws Exception {
        user("ttl_user", "SUPPORT");
        mvc.perform(post("/auth/login").contentType("application/json").content("{\"username\":\"ttl_user\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresInSeconds").value(7 * 60));
    }

    @Test
    void passwordMinimumLengthComesFromTheEnvironmentButNeverBelow12() {
        try {
            new UserService(null, null, null, null, 16);
            assertThrows(IllegalArgumentException.class, () -> UserService.checkPassword("abcdefghij12"));   // 12 caractères < 16
            assertDoesNotThrow(() -> UserService.checkPassword("abcdefghijklm1234"));
            new UserService(null, null, null, null, 4);   // valeur trop basse : plancher de 12
            assertThrows(IllegalArgumentException.class, () -> UserService.checkPassword("abc12"));
            assertDoesNotThrow(() -> UserService.checkPassword("abcdefghij12"));
        } finally {
            new UserService(null, null, null, null, 12);
        }
    }

    @Test
    void routingFilesAreLoadedFromThePathsGivenInTheEnvironment(@TempDir Path dir) throws Exception {
        Path ranges = dir.resolve("ranges.csv"), ported = dir.resolve("ported.csv");
        String prefix = "7" + (1000 + (int) (Math.random() * 8000));   // 5 chiffres + 3 = numéro de 8 chiffres
        Files.writeString(ranges, "prefix;operator\n# commentaire\n" + prefix + ";ORANGE\n");
        Files.writeString(ported, "msisdn;operator\n" + prefix + "123;OOREDOO\n");
        var startupWithFiles = new StartupData(ranges.toString(), ported.toString(), "", routing, operators, shortCodes, partners, services, keywords, tariffs, new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Clock.systemUTC());
        startupWithFiles.apply();
        assertEquals("ORANGE", routing.resolve("+216" + prefix + "999").orElseThrow().getCode());
        assertEquals("OOREDOO", routing.resolve("+216" + prefix + "123").orElseThrow().getCode(), "numéro porté prioritaire");
        // fichier absent : journalisé, le démarrage n'est pas interrompu
        assertDoesNotThrow(() -> new StartupData(dir.resolve("absent.csv").toString(), "", "", routing, operators, shortCodes, partners, services, keywords, tariffs, new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Clock.systemUTC()).apply());
    }

    @Test
    void catalogFileCreatesDraftServicesAndUnapprovedTariffsOnlyOnce() throws Exception {
        String n = String.valueOf(System.nanoTime());
        var tt = operators.findByCode("TT").orElseThrow();
        var sc = new ShortCode();
        sc.setNumber("9" + n.substring(n.length() - 6)); sc.setOperator(tt);
        sc = shortCodes.save(sc);
        String json = "{\"partners\":[{\"name\":\"P-" + n + "\",\"sharePercent\":25}],\"services\":[{\"name\":\"S-" + n + "\",\"type\":\"VOTE\",\"operator\":\"TT\",\"shortCode\":\"" + sc.getNumber()
                + "\",\"partner\":\"P-" + n + "\",\"keywords\":[\"vote\",\"VOTE\"],\"tariffs\":[{\"eventType\":\"MT\",\"grossAmount\":0.5,\"operatorPercent\":40,\"taxPercent\":19}]},"
                + "{\"name\":\"X-" + n + "\",\"type\":\"VOTE\",\"operator\":\"TT\",\"shortCode\":\"inconnu\"}]}";
        var r = startup.importCatalog(json);
        assertEquals(1, r.partners()); assertEquals(1, r.services()); assertEquals(1, r.keywords()); assertEquals(1, r.tariffs());
        assertEquals(1, r.warnings().size(), "service avec short code inconnu ignoré et signalé");
        var svc = services.findAll().stream().filter(s -> s.getName().equals("S-" + n)).findFirst().orElseThrow();
        assertEquals(ServiceStatus.DRAFT, svc.getStatus(), "jamais actif sans décision humaine");
        var tariff = tariffs.findAll().stream().filter(t -> t.getService().getId().equals(svc.getId())).findFirst().orElseThrow();
        assertFalse(tariff.isApproved(), "l'approbation à quatre yeux reste obligatoire");
        assertEquals("bootstrap", tariff.getCreatedBy());
        // rejouer le fichier ne crée rien de plus
        var again = startup.importCatalog(json);
        assertEquals(0, again.partners() + again.services() + again.keywords() + again.tariffs());
    }
}
