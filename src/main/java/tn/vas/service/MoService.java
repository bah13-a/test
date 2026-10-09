package tn.vas.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.config.VasProperties;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

/** Moteur VAS côté MO : dédoublonnage, routage opérateur+short code+keyword, STOP/AIDE, consentement, limites, réponses MT. */
@Service
public class MoService {
    private static final List<String> STOP_WORDS = List.of("STOP", "DESABO", "ARRET", "UNSUBSCRIBE", "الغاء", "إلغاء");
    private static final List<String> HELP_WORDS = List.of("AIDE", "HELP", "مساعدة");
    private static final List<String> YES_WORDS = List.of("OUI", "YES", "OK", "نعم", "ايه");

    private final OperatorRepo operators;
    private final ShortCodeRepo shortCodes;
    private final KeywordRepo keywords;
    private final MoRepo mos;
    private final SubscriptionRepo subs;
    private final ConsentRepo consents;
    private final MtService mt;
    private final AuditService audit;
    private final VasProperties props;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final ReplyRepo replies;
    private final WebhookService webhooks;
    private final RuleRepo rules;

    public MoService(OperatorRepo operators, ShortCodeRepo shortCodes, KeywordRepo keywords, MoRepo mos,
                     SubscriptionRepo subs, ConsentRepo consents, MtService mt, AuditService audit,
                     VasProperties props, Clock clock, io.micrometer.core.instrument.MeterRegistry metrics,
                     ReplyRepo replies, WebhookService webhooks, RuleRepo rules) {
        this.rules = rules;
        this.metrics = metrics;
        this.replies = replies;
        this.webhooks = webhooks;
        this.operators = operators;
        this.shortCodes = shortCodes;
        this.keywords = keywords;
        this.mos = mos;
        this.subs = subs;
        this.consents = consents;
        this.mt = mt;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public MoOutcome handle(String connector, String from, String to, String content, String operatorMsgId) {
        Operator op = operators.findByJasminConnector(connector)
                .or(() -> operators.findByCode(connector == null ? "" : connector.toUpperCase(Locale.ROOT)))
                .orElseThrow(() -> new IllegalArgumentException("Opérateur/connecteur inconnu : " + connector));
        String msisdn = Text.normalizeMsisdn(from);
        if (msisdn == null) throw new IllegalArgumentException("MSISDN invalide");
        String text = content == null ? "" : content.trim();
        var now = clock.instant();

        // 1. Dédoublonnage : par message_id opérateur (contrainte unique) et par contenu sur fenêtre configurable
        String dedupKey = operatorMsgId != null && !operatorMsgId.isBlank() ? "id:" + operatorMsgId : "u:" + java.util.UUID.randomUUID();
        if (mos.existsByOperatorAndDedupKey(op, dedupKey)) return count(MoOutcome.DUPLICATE);
        if (props.moDedupSeconds() > 0 && mos.existsByOperatorAndMsisdnAndShortCodeAndContentAndReceivedAtAfter(
                op, msisdn, to, text, now.minus(Duration.ofSeconds(props.moDedupSeconds())))) {
            return count(MoOutcome.DUPLICATE);
        }

        var mo = new MoMessage();
        mo.setOperator(op);
        mo.setDedupKey(dedupKey);
        mo.setOperatorMsgId(operatorMsgId);
        mo.setMsisdn(msisdn);
        mo.setShortCode(to);
        mo.setContent(text.length() > 1000 ? text.substring(0, 1000) : text);
        mo.setReceivedAt(now);

        ShortCode sc = shortCodes.findByNumberAndOperator(to, op).orElse(null);
        String[] tokens = text.isEmpty() ? new String[0] : text.split("\\s+");
        String first = tokens.length > 0 ? tokens[0].toUpperCase(Locale.ROOT) : "";

        MoOutcome outcome;
        if (sc == null || !"ACTIVE".equals(sc.getStatus())) {
            outcome = MoOutcome.BLOCKED;
        } else if (STOP_WORDS.contains(first)) {
            outcome = stop(mo, sc, tokens);
        } else if (HELP_WORDS.contains(first)) {
            outcome = help(mo, sc, tokens);
        } else if (YES_WORDS.contains(first) && confirmPending(mo, sc)) {
            outcome = MoOutcome.ROUTED;
        } else {
            outcome = route(mo, sc, first, text);
        }
        mo.setOutcome(outcome);
        mos.save(mo);
        return count(outcome);
    }

    private MoOutcome count(MoOutcome o) {
        metrics.counter("vas.mo", "outcome", o.name()).increment();
        return o;
    }

    private MoOutcome route(MoMessage mo, ShortCode sc, String word, String text) {
        List<Keyword> found = keywords.findByShortCodeAndWord(sc, word);
        if (found.isEmpty()) return MoOutcome.UNKNOWN_KEYWORD; // rejet silencieux (réponse configurable via keyword générique)
        VasService svc = found.get(0).getService();
        mo.setService(svc);
        var now = clock.instant();
        if (svc.getStatus() != ServiceStatus.ACTIVE
                || (svc.getOpensAt() != null && now.isBefore(svc.getOpensAt()))
                || (svc.getClosesAt() != null && now.isAfter(svc.getClosesAt()))) {
            reply(mo, svc, text(svc, Messages.CLOSED, svc.getReplyClosed(), mo), null);
            return MoOutcome.SERVICE_CLOSED;
        }
        if (svc.isRegulated() && !svc.isRegulatoryApproved()) {
            audit.log("MO_BLOCKED_REGULATORY", "service:" + svc.getId(), tn.vas.web.AdminController.mask(mo.getMsisdn()));
            return MoOutcome.BLOCKED;
        }
        if (rules.blacklisted(mo.getMsisdn(), svc)
                || (rules.whitelistSize(svc) > 0 && !rules.whitelisted(mo.getMsisdn(), svc))) {
            audit.log("MO_BLOCKED_RULE", "service:" + svc.getId(), tn.vas.web.AdminController.mask(mo.getMsisdn()));
            return MoOutcome.BLOCKED;
        }
        if (svc.getMaxActionsPerMsisdn() > 0 && mos.countByServiceAndMsisdnAndOutcome(svc, mo.getMsisdn(), MoOutcome.ROUTED)
                >= svc.getMaxActionsPerMsisdn()) {
            reply(mo, svc, text(svc, Messages.LIMIT, svc.getReplyLimit(), mo), null);
            return MoOutcome.LIMIT_REACHED;
        }
        if (svc.getType() == ServiceType.SUBSCRIPTION) {
            subscribe(mo, svc, text);
        } else {
            reply(mo, svc, text(svc, Messages.OK, svc.getReplyOk(), mo), EventType.MT);
        }
        webhooks.enqueueMo(mo, svc);
        return MoOutcome.ROUTED;
    }

    private void subscribe(MoMessage mo, VasService svc, String text) {
        var now = clock.instant();
        Subscription sub = subs.findByMsisdnAndService(mo.getMsisdn(), svc).orElseGet(() -> {
            var s = new Subscription();
            s.setMsisdn(mo.getMsisdn());
            s.setService(svc);
            return s;
        });
        if (sub.getStatus() == SubStatus.ACTIVE) {
            reply(mo, svc, text(svc, Messages.ALREADY, null, mo), null);
            return;
        }
        consent(mo.getMsisdn(), svc, "OPT_IN_REQUEST", "SMS", text);
        if (svc.getConsentMode() == ConsentMode.DOUBLE_OPT_IN) {
            sub.setStatus(SubStatus.PENDING_CONFIRMATION);
            subs.save(sub);
            reply(mo, svc, String.format(text(svc, Messages.CONFIRM, null, mo), svc.getName()), null);
        } else {
            activate(sub, mo, svc, text);
        }
    }

    private boolean confirmPending(MoMessage mo, ShortCode sc) {
        var pending = subs.findByMsisdnShortCodeStatus(mo.getMsisdn(), sc, SubStatus.PENDING_CONFIRMATION);
        if (pending.isEmpty()) return false;
        var s = pending.get(0);
        mo.setService(s.getService());
        consent(mo.getMsisdn(), s.getService(), "OPT_IN_CONFIRMED", "SMS", mo.getContent());
        activate(s, mo, s.getService(), mo.getContent());
        return true;
    }

    private void activate(Subscription sub, MoMessage mo, VasService svc, String proof) {
        var now = clock.instant();
        sub.setStatus(SubStatus.ACTIVE);
        sub.setActivatedAt(now);
        sub.setStoppedAt(null);
        sub.setNextRenewalAt(now.plus(Duration.ofDays(30)));
        subs.save(sub);
        consent(mo.getMsisdn(), svc, "ACTIVATED", "SMS", proof);
        reply(mo, svc, text(svc, Messages.SUB_OK, svc.getReplyOk(), mo), EventType.SUBSCRIPTION);
    }

    private MoOutcome stop(MoMessage mo, ShortCode sc, String[] tokens) {
        List<Subscription> targets = java.util.stream.Stream.concat(
                        subs.findByMsisdnShortCodeStatus(mo.getMsisdn(), sc, SubStatus.ACTIVE).stream(),
                        subs.findByMsisdnShortCodeStatus(mo.getMsisdn(), sc, SubStatus.PENDING_CONFIRMATION).stream())
                .filter(s -> tokens.length < 2 || keywords.findByService(s.getService()).stream()
                        .anyMatch(k -> k.getWord().equalsIgnoreCase(tokens[1])))
                .toList();
        for (var s : targets) {
            s.setStatus(SubStatus.STOPPED);
            s.setStoppedAt(clock.instant());
            s.setNextRenewalAt(null); // blocage immédiat de tout renouvellement
            subs.save(s);
            consent(mo.getMsisdn(), s.getService(), "STOPPED", "SMS", mo.getContent());
            mo.setService(s.getService());
            reply(mo, s.getService(), text(s.getService(), Messages.STOP, s.getService().getReplyStop(), mo), null);
        }
        return MoOutcome.STOPPED;
    }

    private MoOutcome help(MoMessage mo, ShortCode sc, String[] tokens) {
        keywords.findByShortCode(sc).stream()
                .filter(k -> tokens.length < 2 || k.getWord().equalsIgnoreCase(tokens[1])).findFirst().ifPresent(k -> {
                    mo.setService(k.getService());
                    reply(mo, k.getService(), text(k.getService(), Messages.HELP, k.getService().getReplyHelp(), mo), null);
                });
        return MoOutcome.HELP;
    }

    private void consent(String msisdn, VasService svc, String action, String channel, String proof) {
        var c = new ConsentRecord();
        c.setMsisdn(msisdn);
        c.setService(svc);
        c.setAction(action);
        c.setChannel(channel);
        c.setProofText(proof);
        c.setTermsVersion("v1");
        c.setAt(clock.instant());
        consents.save(c);
    }

    private void reply(MoMessage mo, VasService svc, String text, EventType billing) {
        if (mo.getId() == null) { // nécessaire pour référencer le MO dans le MT ; l'issue finale est posée par handle()
            if (mo.getOutcome() == null) mo.setOutcome(MoOutcome.ROUTED);
            mos.save(mo);
        }
        mt.submit(new MtService.Request(mo.getOperator(), svc, mo.getMsisdn(), mo.getShortCode(), text,
                billing == null ? Priority.TRANSACTIONAL : Priority.CONFIRMATION, null, billing, mo.getId(), null,
                Duration.ofHours(24)));
    }

    /** Texte de réponse : surcharge par service+langue, puis champ legacy du service (langue par défaut), puis catalogue système. */
    private String text(VasService svc, String kind, String legacy, MoMessage mo) {
        String lang = Messages.detect(mo.getContent(), svc.getDefaultLang());
        var custom = replies.findByServiceAndLangAndKind(svc, lang, kind);
        if (custom.isPresent()) return custom.get().getText();
        if (legacy != null && !legacy.isBlank() && lang.equals(svc.getDefaultLang())) return legacy;
        return Messages.text(lang, kind);
    }
}
