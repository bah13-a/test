package tn.vas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

/**
 * Données de démarrage fournies par fichiers (chemins dans le .env), appliquées sans intervention manuelle :
 *  - ROUTING_RANGES_FILE / ROUTING_PORTED_FILE : plages de numéros et numéros portés (CSV « préfixe;opérateur » / « numéro;opérateur »), mis à jour à chaque démarrage ;
 *  - CATALOG_FILE : partenaires, services, mots-clés et tarifs initiaux (JSON), créés seulement s'ils n'existent pas.
 * Garde-fous : les services créés sont en BROUILLON (activation par un humain) et les tarifs sont créés NON APPROUVÉS par « bootstrap » :
 * l'approbation à quatre yeux reste obligatoire. Un fichier absent ou invalide est journalisé en erreur sans empêcher le démarrage.
 */
@Component
@Order(20) // après OperatorSync (10) : opérateurs et short codes existent
public class StartupData {
    private static final Logger log = LoggerFactory.getLogger(StartupData.class);
    private final String rangesFile, portedFile, catalogFile;
    private final RoutingService routing;
    private final OperatorRepo operators;
    private final ShortCodeRepo shortCodes;
    private final PartnerRepo partners;
    private final ServiceRepo services;
    private final KeywordRepo keywords;
    private final TariffRepo tariffs;
    private final ObjectMapper json;
    private final Clock clock;

    public StartupData(@Value("${vas.bootstrap.routing-ranges-file:}") String rangesFile, @Value("${vas.bootstrap.routing-ported-file:}") String portedFile,
                       @Value("${vas.bootstrap.catalog-file:}") String catalogFile, RoutingService routing, OperatorRepo operators, ShortCodeRepo shortCodes,
                       PartnerRepo partners, ServiceRepo services, KeywordRepo keywords, TariffRepo tariffs, ObjectMapper json, Clock clock) {
        this.rangesFile = rangesFile; this.portedFile = portedFile; this.catalogFile = catalogFile; this.routing = routing; this.operators = operators;
        this.shortCodes = shortCodes; this.partners = partners; this.services = services; this.keywords = keywords; this.tariffs = tariffs; this.json = json; this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    public void apply() {
        load("ranges", rangesFile);
        load("ported", portedFile);
        if (!catalogFile.isBlank()) {
            try {
                var r = importCatalog(Files.readString(Path.of(catalogFile)));
                log.info("catalogue de démarrage : {}", r);
            } catch (IOException | RuntimeException e) {
                log.error("catalogue de démarrage (CATALOG_FILE={}) non appliqué : {}", catalogFile, e.toString());
            }
        }
    }

    private void load(String kind, String file) {
        if (file == null || file.isBlank()) return;
        try {
            var r = routing.importCsv(kind, Files.readAllLines(Path.of(file)), "fichier " + Path.of(file).getFileName());
            log.info("routage ({}) depuis {} : {} créés, {} mis à jour, {} rejetés{}", kind, file, r.created(), r.updated(), r.rejected(), r.errors().isEmpty() ? "" : " " + r.errors());
        } catch (IOException | RuntimeException e) {
            log.error("import du routage ({}) impossible depuis {} : {}", kind, file, e.toString());
        }
    }

    public record CatalogResult(int partners, int services, int keywords, int tariffs, List<String> warnings) {}

    @Transactional
    public CatalogResult importCatalog(String content) throws IOException {
        JsonNode root = json.readTree(content);
        int np = 0, ns = 0, nk = 0, nt = 0;
        List<String> warn = new ArrayList<>();
        for (JsonNode p : root.path("partners")) {
            String name = p.path("name").asText("").strip();
            if (name.isEmpty() || partners.findAll().stream().anyMatch(x -> x.getName().equalsIgnoreCase(name))) continue;
            var e = new Partner();
            e.setName(name);
            e.setSharePercent(p.has("sharePercent") ? new BigDecimal(p.get("sharePercent").asText()) : BigDecimal.ZERO);
            if (p.hasNonNull("webhookUrl")) e.setWebhookUrl(p.get("webhookUrl").asText());
            if (p.hasNonNull("webhookSecret")) e.setWebhookSecret(p.get("webhookSecret").asText());
            if (p.has("maxTps")) e.setMaxTps(p.get("maxTps").asInt());
            partners.save(e);
            np++;
        }
        for (JsonNode s : root.path("services")) {
            String name = s.path("name").asText("").strip();
            if (name.isEmpty()) continue;
            var existing = services.findAll().stream().filter(x -> x.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
            VasService svc = existing;
            if (svc == null) {
                var op = operators.findByCode(s.path("operator").asText("").toUpperCase(Locale.ROOT)).orElse(null);
                var sc = op == null ? null : shortCodes.findByNumberAndOperator(s.path("shortCode").asText(""), op).orElse(null);
                if (sc == null) { warn.add("service « " + name + " » ignoré : opérateur ou short code inconnu"); continue; }
                svc = new VasService();
                svc.setName(name);
                svc.setType(ServiceType.valueOf(s.path("type").asText("ALERT").toUpperCase(Locale.ROOT)));
                svc.setShortCode(sc);
                svc.setStatus(ServiceStatus.DRAFT);
                if (s.hasNonNull("partner")) svc.setPartner(partners.findAll().stream().filter(x -> x.getName().equalsIgnoreCase(s.get("partner").asText())).findFirst().orElse(null));
                if (s.hasNonNull("consentMode")) svc.setConsentMode(ConsentMode.valueOf(s.get("consentMode").asText().toUpperCase(Locale.ROOT)));
                if (s.hasNonNull("defaultLang")) svc.setDefaultLang(s.get("defaultLang").asText());
                svc.setRegulated(s.path("regulated").asBoolean(false));
                svc.setMaxActionsPerMsisdn(s.path("maxActionsPerMsisdn").asInt(0));
                svc.setMaxTps(s.path("maxTps").asInt(0));
                for (var f : new String[]{"replyOk", "replyStop", "replyHelp", "replyLimit", "replyClosed"}) {
                    if (!s.hasNonNull(f)) continue;
                    String v = s.get(f).asText();
                    switch (f) { case "replyOk" -> svc.setReplyOk(v); case "replyStop" -> svc.setReplyStop(v); case "replyHelp" -> svc.setReplyHelp(v);
                        case "replyLimit" -> svc.setReplyLimit(v); default -> svc.setReplyClosed(v); }
                }
                svc = services.save(svc);
                ns++;
            }
            for (JsonNode k : s.path("keywords")) {
                String w = k.asText("").strip().toUpperCase(Locale.ROOT);
                if (w.isEmpty() || keywords.findByService(svc).stream().anyMatch(x -> x.getWord().equalsIgnoreCase(w))) continue;
                var kw = new Keyword();
                kw.setService(svc);
                kw.setWord(w);
                keywords.save(kw);
                nk++;
            }
            for (JsonNode t : s.path("tariffs")) {
                EventType type = EventType.valueOf(t.path("eventType").asText("MT").toUpperCase(Locale.ROOT));
                final var current = svc;
                if (tariffs.findAll().stream().anyMatch(x -> x.getService().getId().equals(current.getId()) && x.getEventType() == type)) continue;
                var tf = new Tariff();
                tf.setService(svc);
                tf.setEventType(type);
                tf.setGrossAmount(new BigDecimal(t.path("grossAmount").asText("0")));
                tf.setOperatorPercent(new BigDecimal(t.path("operatorPercent").asText("0")));
                tf.setTaxPercent(new BigDecimal(t.path("taxPercent").asText("0")));
                tf.setEffectiveFrom(clock.instant());
                tf.setApproved(false);
                tf.setCreatedBy("bootstrap");
                tariffs.save(tf);
                nt++;
            }
        }
        return new CatalogResult(np, ns, nk, nt, warn);
    }
}
