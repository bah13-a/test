package tn.vas.dev;

import java.math.BigDecimal;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

/**
 * Démonstration des options (profil dev uniquement, après DevDataSeeder) : menu USSD *123# et serveur vocal 80100100 sur le vote,
 * compte WhatsApp « demo-phone-id » rattaché au 85500 avec un modèle approuvé, tarifs USSD / IVR / DCB, droits DCB et WhatsApp pour
 * le client de démonstration et accès SMPP « demo » / « demo1234 » (port vas.smpp-server.port).
 */
@Component
@Profile("dev")
public class OptionsDemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(OptionsDemoSeeder.class);
    static final String MENU = """
            {"text":"Vote Meilleur Joueur\\n1. Voter\\n2. Resultat du jour","options":{
              "1":{"text":"1. Joueur A\\n2. Joueur B","options":{
                    "1":{"text":"Vote A pris en compte, confirmation par SMS","action":"KEYWORD","param":"VOTE A"},
                    "2":{"text":"Vote B pris en compte, confirmation par SMS","action":"KEYWORD","param":"VOTE B"}}},
              "2":{"text":"Joueur A en tete (0,300 TND)","action":"CHARGE"}}}""";
    private final ServiceRepo services;
    private final TariffRepo tariffs;
    private final MenuRepo menus;
    private final ChatChannelRepo chats;
    private final ChatTemplateRepo templates;
    private final ApiClientRepo clients;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public OptionsDemoSeeder(ServiceRepo services, TariffRepo tariffs, MenuRepo menus, ChatChannelRepo chats, ChatTemplateRepo templates,
                             ApiClientRepo clients, PasswordEncoder encoder, Clock clock) {
        this.services = services;
        this.tariffs = tariffs;
        this.menus = menus;
        this.chats = chats;
        this.templates = templates;
        this.clients = clients;
        this.encoder = encoder;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(110)
    @Transactional
    public void seed() {
        if (menus.count() > 0) return;
        var vote = services.findAll().stream().filter(s -> s.getName().startsWith("Vote Meilleur Joueur")).findFirst().orElse(null);
        if (vote == null) return;   // données de démonstration de base absentes
        tariff(vote, EventType.USSD, "0.300");
        tariff(vote, EventType.IVR, "0.500");
        tariff(vote, EventType.DCB, "2.000");
        for (Channel ch : new Channel[]{Channel.USSD, Channel.IVR}) {
            var m = new MenuDefinition();
            m.setChannel(ch);
            m.setCode(ch == Channel.USSD ? "*123#" : "80100100");
            m.setService(vote);
            m.setDefinition(MENU);
            m.setUpdatedBy("demo");
            m.setUpdatedAt(clock.instant());
            menus.save(m);
        }
        var wa = new ChatChannel();
        wa.setKind(Channel.WHATSAPP);
        wa.setAddress("demo-phone-id");
        wa.setShortCode(vote.getShortCode());
        chats.save(wa);
        var t = new ChatTemplate();
        t.setService(vote);
        t.setName("rappel_vote");
        t.setLang("fr");
        t.setCategory("UTILITY");
        t.setBody("Bonjour {{1}}, le vote se termine ce soir à {{2}}.");
        t.setParamCount(2);
        t.setApproved(true);
        t.setCreatedBy("manager");
        t.setApprovedBy("admin");
        t.setCreatedAt(clock.instant());
        templates.save(t);
        clients.findAll().stream().filter(c -> "client-demo".equals(c.getName())).findFirst().ifPresent(c -> {
            c.setScopes(c.getScopes() + ",dcb:charge,chat:send");
            c.setSmppSystemId("demo");
            c.setSmppPasswordHash(encoder.encode("demo1234"));
            c.setSmppMo(true);
            clients.save(c);
        });
        log.info("[dev] options de démonstration : USSD *123#, IVR 80100100, WhatsApp demo-phone-id, modèle rappel_vote, SMPP demo / demo1234");
    }

    private void tariff(VasService s, EventType e, String gross) {
        var t = new Tariff();
        t.setService(s);
        t.setEventType(e);
        t.setGrossAmount(new BigDecimal(gross));
        t.setOperatorPercent(new BigDecimal("40"));
        t.setTaxPercent(new BigDecimal("19"));
        t.setEffectiveFrom(clock.instant().minusSeconds(60));
        t.setApproved(true);
        t.setCreatedBy("demo");
        tariffs.save(t);
    }
}
