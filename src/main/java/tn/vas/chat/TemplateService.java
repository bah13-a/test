package tn.vas.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.config.ChatProperties;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.fraud.FraudService;
import tn.vas.repo.Repos.*;
import tn.vas.service.MtService;
import tn.vas.service.Text;

/**
 * Envois WhatsApp à l'initiative du partenaire (hors fenêtre de 24 h) : uniquement par modèle approuvé, à un abonné ayant donné son
 * accord WhatsApp pour ce service (opt-in tracé, révoqué par tout STOP ultérieur), après listes noire / blanche et antifraude.
 */
@Service
public class TemplateService {
    public static final String OPT_IN = "OPT_IN_WHATSAPP";
    private static final Set<String> GRANTING = Set.of(OPT_IN, "ACTIVATED", "OPT_IN_CONFIRMED");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChatTemplateRepo templates;
    private final ConsentRepo consents;
    private final RuleRepo rules;
    private final FraudService fraud;
    private final MtService mt;
    private final ChatProperties props;
    private final Clock clock;

    public TemplateService(ChatTemplateRepo templates, ConsentRepo consents, RuleRepo rules, FraudService fraud, MtService mt, ChatProperties props, Clock clock) {
        this.templates = templates;
        this.consents = consents;
        this.rules = rules;
        this.fraud = fraud;
        this.mt = mt;
        this.props = props;
        this.clock = clock;
    }

    /** Accord WhatsApp : au moins un accord sur le canal WhatsApp, et le dernier consentement (tous canaux) n'est pas un arrêt. */
    public boolean optedIn(String msisdn, VasService svc) {
        List<ConsentRecord> all = consents.findByMsisdnAndServiceOrderByAtAsc(msisdn, svc);
        boolean whatsapp = all.stream().anyMatch(c -> "WHATSAPP".equals(c.getChannel()) && GRANTING.contains(c.getAction()));
        return whatsapp && !all.isEmpty() && !"STOPPED".equals(all.get(all.size() - 1).getAction());
    }

    @Transactional
    public ConsentRecord recordOptIn(String msisdn, VasService svc, String proof) {
        var c = new ConsentRecord();
        c.setMsisdn(msisdn);
        c.setService(svc);
        c.setAction(OPT_IN);
        c.setChannel("WHATSAPP");
        c.setProofText(proof.length() > 1000 ? proof.substring(0, 1000) : proof);
        c.setTermsVersion("v1");
        c.setAt(clock.instant());
        return consents.save(c);
    }

    @Transactional
    public MtMessage send(ApiClient client, VasService svc, String rawTo, String name, String lang, List<String> params, String clientRef) {
        if (props.disabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "WhatsApp non activé");
        String msisdn = Text.normalizeMsisdn(rawTo);
        if (msisdn == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MSISDN invalide");
        if (svc.getStatus() != ServiceStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "service inactif");
        ChatTemplate t = templates.findByServiceAndNameAndLang(svc, name, lang == null ? "fr" : lang)
                .filter(ChatTemplate::isApproved).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "modèle inconnu ou non approuvé"));
        List<String> p = params == null ? List.of() : params;
        if (p.size() != t.getParamCount()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "le modèle attend " + t.getParamCount() + " paramètre(s)");
        if (p.stream().anyMatch(x -> x == null || x.isBlank() || x.length() > 1024 || x.contains("\n")))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "paramètre vide, trop long ou multiligne");
        if (!optedIn(msisdn, svc)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "aucun accord WhatsApp de l'abonné pour ce service");
        if (rules.blacklisted(msisdn, svc) || (rules.whitelistSize(svc) > 0 && !rules.whitelisted(msisdn, svc)) || fraud.blocked(msisdn))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "destinataire exclu");
        String text = render(t.getBody(), p);
        String payload;
        try {
            payload = JSON.writeValueAsString(Map.of("template", t.getName(), "lang", t.getLang(), "params", p));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var m = mt.submit(new MtService.Request(svc.getShortCode().getOperator(), svc, msisdn, svc.getShortCode().getNumber(), text, Priority.TRANSACTIONAL,
                clientRef, null, null, client.getId(), Duration.ofHours(24), null, null, Channel.WHATSAPP));
        m.setPayload(payload);
        return m;
    }

    /** Aperçu du texte envoyé : {{1}}..{{n}} remplacés par les paramètres. */
    public static String render(String body, List<String> params) {
        String out = body;
        for (int i = 0; i < params.size(); i++) out = out.replace("{{" + (i + 1) + "}}", params.get(i));
        return out;
    }

    /** Nombre de paramètres déclarés dans un corps de modèle ({{1}}..{{n}} consécutifs). */
    public static int countParams(String body) {
        int n = 0;
        while (body.contains("{{" + (n + 1) + "}}")) n++;
        return n;
    }
}
