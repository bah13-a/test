package tn.vas.channel;

import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.Enums.BillingStatus;
import tn.vas.domain.Enums.Channel;
import tn.vas.domain.Enums.EventType;
import tn.vas.domain.Enums.ServiceStatus;
import tn.vas.domain.IvrCall;
import tn.vas.fraud.FraudService;
import tn.vas.repo.Repos.IvrCallRepo;
import tn.vas.repo.Repos.MenuRepo;
import tn.vas.service.LedgerService;
import tn.vas.service.Text;

/**
 * Serveur vocal interactif piloté par webhook : le serveur de téléphonie (Asterisk via infra/ivr/agi-bridge.py, ou la plateforme vocale
 * de l'opérateur) appelle /callbacks/ivr à l'arrivée de l'appel (start), à chaque saisie DTMF (input) et au raccroché (hangup).
 * La réponse indique le texte à prononcer (synthèse vocale ou fichier son de même nom) et s'il faut attendre une touche ou raccrocher.
 * Facturation à la minute entamée au raccroché (tarif IVR approuvé du service), en plus d'éventuelles feuilles CHARGE.
 */
@Service
public class IvrService {
    private final MenuRepo menus;
    private final IvrCallRepo calls;
    private final MenuActions actions;
    private final LedgerService ledger;
    private final FraudService fraud;
    private final Clock clock;

    public IvrService(MenuRepo menus, IvrCallRepo calls, MenuActions actions, LedgerService ledger, FraudService fraud, Clock clock) {
        this.menus = menus;
        this.calls = calls;
        this.actions = actions;
        this.ledger = ledger;
        this.fraud = fraud;
        this.clock = clock;
    }

    /** action = « prompt » (prononcer puis attendre maxDigits touches) ou « hangup » (prononcer puis raccrocher). */
    public record Reply(String action, String text, String lang, int maxDigits) {
        static Reply hangup(String text, String lang) { return new Reply("hangup", text, lang, 0); }
        static Reply prompt(String text, String lang) { return new Reply("prompt", text, lang, 1); }
    }

    @Transactional
    public Reply handle(String callId, String rawMsisdn, String number, String event, String digits, Integer durationSeconds) {
        String msisdn = Text.normalizeMsisdn(rawMsisdn);
        if (msisdn == null || callId == null || callId.isBlank()) return Reply.hangup(MenuActions.UNAVAILABLE, "fr");
        var now = clock.instant();
        var existing = calls.findByCallId(callId);
        if ("hangup".equals(event)) {
            existing.ifPresent(c -> hangup(c, durationSeconds));
            return Reply.hangup("", "fr");
        }
        var menu = existing.map(IvrCall::getMenu)
                .or(() -> menus.findByChannelAndCode(Channel.IVR, number == null ? "" : number.strip()).filter(m -> m.isActive())).orElse(null);
        if (menu == null) return Reply.hangup(MenuActions.UNAVAILABLE, "fr");
        var svc = menu.getService();
        String lang = svc.getDefaultLang() == null ? "fr" : svc.getDefaultLang();
        if (svc.getStatus() != ServiceStatus.ACTIVE || fraud.blocked(msisdn)) return Reply.hangup(MenuActions.UNAVAILABLE, lang);
        MenuTree tree = MenuTree.parse(menu.getDefinition(), false);
        IvrCall call = existing.orElse(null);
        if (call == null) {
            if (!fraud.canSpendFor(msisdn, svc, EventType.IVR)) return Reply.hangup(MenuActions.UNAVAILABLE, lang);
            call = new IvrCall();
            call.setCallId(callId);
            call.setMsisdn(msisdn);
            call.setOperator(svc.getShortCode().getOperator());
            call.setMenu(menu);
            call.setPath("");
            call.setStatus("ACTIVE");
            call.setStartedAt(now);
            calls.save(call);
            return Reply.prompt(tree.root().text(), lang);
        }
        if (!"ACTIVE".equals(call.getStatus())) return Reply.hangup("", lang);
        String path = digits == null || digits.isBlank() ? call.getPath() : (call.getPath().isEmpty() ? digits.strip() : call.getPath() + "*" + digits.strip());
        MenuTree.Position pos = tree.at(path);
        call.setPath(pos.path());
        Reply reply;
        if (!pos.node().leaf()) {
            reply = Reply.prompt(pos.invalid() ? UssdService.INVALID + " " + pos.node().text() : pos.node().text(), lang);
        } else {
            reply = Reply.hangup(actions.execute(menu, pos.node(), Channel.IVR, call.getOperator(), msisdn, callId, pos.path()), lang);
        }
        calls.save(call);
        return reply;
    }

    /** Raccroché : durée retenue = celle de la plateforme vocale si fournie, sinon l'horloge ; facturation à la minute entamée. */
    private void hangup(IvrCall c, Integer durationSeconds) {
        if ("ENDED".equals(c.getStatus())) return;   // raccroché rejoué : pas de seconde facturation
        var now = clock.instant();
        int secs = durationSeconds != null && durationSeconds >= 0 ? durationSeconds : (int) Duration.between(c.getStartedAt(), now).toSeconds();
        c.setStatus("ENDED");
        c.setEndedAt(now);
        c.setDurationSeconds(secs);
        calls.save(c);
        int minutes = (secs + 59) / 60;
        var svc = c.getMenu().getService();
        String eventId = "IVR-" + c.getCallId();
        ledger.record(eventId, EventType.IVR, c.getOperator(), svc, svc.getShortCode().getNumber(), c.getMsisdn(), c.getCallId(), minutes)
                .ifPresent(e -> ledger.transition(eventId, BillingStatus.CHARGED));
    }
}
