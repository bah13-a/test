package tn.vas.dev;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tn.vas.config.VasProperties;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;
import tn.vas.security.ApiKeyFilter;
import tn.vas.security.UserService;
import tn.vas.service.MoService;

/**
 * Données de démonstration du profil dev (jamais chargé en pro) : partenaire avec webhook vers le puits local, 4 services couvrant
 * vote / abonnement / quiz réglementé / contenu premium, tarifs approuvés, un utilisateur par rôle, une clé API fixe, du trafic MO simulé.
 */
@Component
@Profile("dev")
public class DevDataSeeder {
    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    public static final String DEV_PASSWORD = "Dev-pass-12345";
    private final VasProperties props;
    private final OperatorRepo operators;
    private final ShortCodeRepo shortCodes;
    private final PartnerRepo partners;
    private final ServiceRepo services;
    private final KeywordRepo keywords;
    private final TariffRepo tariffs;
    private final ApiClientRepo apiClients;
    private final UserRepo users;
    private final UserService userService;
    private final MoService mo;
    private final Clock clock;

    public DevDataSeeder(VasProperties props, OperatorRepo operators, ShortCodeRepo shortCodes, PartnerRepo partners, ServiceRepo services,
                         KeywordRepo keywords, TariffRepo tariffs, ApiClientRepo apiClients, UserRepo users, UserService userService,
                         MoService mo, Clock clock) {
        this.props = props; this.operators = operators; this.shortCodes = shortCodes; this.partners = partners; this.services = services;
        this.keywords = keywords; this.tariffs = tariffs; this.apiClients = apiClients; this.users = users; this.userService = userService;
        this.mo = mo; this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100) // après OperatorSync (10) et la création du compte initial
    public void seed() {
        if (props.mock() == null || !props.mock().seed() || partners.count() > 0) return;
        seedData();
        if (props.mock().demoTraffic()) demoTraffic();
        log.info("[dev] données de démonstration chargées. Comptes (mot de passe {}) : manager, noc, finance, finance2, support, auditor, club (PARTNER) ; admin / Admin-dev-pass1. Clé API : {}",
                DEV_PASSWORD, props.mock().apiKey());
    }

    void seedData() {
        var p = new Partner();
        p.setName("Club Sportif Démo");
        p.setSharePercent(new BigDecimal("30"));
        p.setWebhookUrl("http://localhost:8080/dev/webhook-sink");
        p.setWebhookSecret("dev-webhook-secret");
        p = partners.save(p);

        var vote = svc("Vote Meilleur Joueur", ServiceType.VOTE, "TT", "85500", p, ConsentMode.SIMPLE_OPT_IN, false, "VOTE");
        var abo = svc("Alertes Sport (abonnement)", ServiceType.SUBSCRIPTION, "ORANGE", "85501", p, ConsentMode.DOUBLE_OPT_IN, false, "SPORT", "ABO");
        var quiz = svc("Quiz Ramadan (réglementé)", ServiceType.QUIZ, "OOREDOO", "85502", null, ConsentMode.SIMPLE_OPT_IN, true, "QUIZ");
        var premium = svc("Contenu premium", ServiceType.PREMIUM_CONTENT, "TT", "85503", null, ConsentMode.SIMPLE_OPT_IN, false, "CODE");
        tariff(vote, EventType.MT, "0.500", "40");
        tariff(abo, EventType.SUBSCRIPTION, "1.000", "40");
        tariff(abo, EventType.RENEWAL, "1.000", "40");
        tariff(quiz, EventType.MT, "0.300", "35");
        tariff(premium, EventType.MT, "1.500", "45");

        String pw = DEV_PASSWORD;
        mkUser("manager", "VAS_MANAGER", pw, null);
        mkUser("noc", "NOC", pw, null);
        mkUser("finance", "FINANCE", pw, null);
        mkUser("finance2", "FINANCE", pw, null);
        mkUser("support", "SUPPORT", pw, null);
        mkUser("auditor", "AUDITOR", pw, null);
        mkUser("club", "PARTNER", pw, p);

        var c = new ApiClient();
        c.setName("client-demo");
        c.setPartner(p);
        c.setScopes("messages:send,messages:read,services:read,reports:read,subscriptions:write");
        c.setKeyHash(ApiKeyFilter.sha256(props.mock().apiKey()));
        apiClients.save(c);
    }

    private VasService svc(String name, ServiceType type, String op, String number, Partner partner, ConsentMode mode, boolean regulated, String... words) {
        var sc = shortCodes.findByNumberAndOperator(number, operators.findByCode(op).orElseThrow()).orElseThrow();
        var s = new VasService();
        s.setName(name);
        s.setType(type);
        s.setShortCode(sc);
        s.setPartner(partner);
        s.setConsentMode(mode);
        s.setRegulated(regulated);
        s.setStatus(ServiceStatus.ACTIVE);
        s.setMaxActionsPerMsisdn(type == ServiceType.VOTE ? 3 : 0);
        s = services.save(s);
        for (String w : words) {
            var k = new Keyword();
            k.setService(s);
            k.setWord(w);
            keywords.save(k);
        }
        return s;
    }

    private void tariff(VasService s, EventType e, String gross, String operatorPct) {
        var t = new Tariff();
        t.setService(s);
        t.setEventType(e);
        t.setGrossAmount(new BigDecimal(gross));
        t.setOperatorPercent(new BigDecimal(operatorPct));
        t.setTaxPercent(new BigDecimal("19"));
        t.setEffectiveFrom(clock.instant().minus(Duration.ofMinutes(5)));
        t.setApproved(true);
        t.setCreatedBy("dev-seed");
        tariffs.save(t);
    }

    private void mkUser(String name, String role, String pw, Partner partner) {
        if (users.findByUsername(name).isEmpty()) userService.create(name, pw, List.of(role), partner);
    }

    private void demoTraffic() {
        int[] i = {0};
        List<String[]> mos = List.of(
                new String[]{"smppc_tt", "85500", "VOTE A"}, new String[]{"smppc_tt", "85500", "VOTE B"}, new String[]{"smppc_tt", "85500", "vote a"},
                new String[]{"smppc_tt", "85503", "CODE"}, new String[]{"smppc_orange", "85501", "SPORT"}, new String[]{"smppc_orange", "85501", "OUI"},
                new String[]{"smppc_ooredoo", "85502", "QUIZ 1"}, new String[]{"smppc_tt", "85500", "BLABLA"}, new String[]{"smppc_tt", "85500", "VOTE أ"});
        for (int n = 0; n < 24; n++) {
            var m = mos.get(n % mos.size());
            String from = "9" + String.format("%07d", 1000000 + (n * 7919) % 9000000);
            try {
                mo.handle(m[0], from, m[1], m[2], "demo-" + (i[0]++));
            } catch (Exception e) {
                log.debug("MO de démonstration ignoré : {}", e.getMessage());
            }
        }
    }
}
