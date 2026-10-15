package tn.vas.channel;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.Enums.Channel;
import tn.vas.domain.Enums.ServiceStatus;
import tn.vas.domain.UssdSession;
import tn.vas.fraud.FraudService;
import tn.vas.repo.Repos.MenuRepo;
import tn.vas.repo.Repos.UssdSessionRepo;
import tn.vas.service.Text;

/**
 * Sessions USSD (MO-initiated) : la passerelle de l'opérateur appelle /callbacks/ussd à chaque saisie ; la réponse « CON texte » garde
 * la session ouverte, « END texte » la termine (convention des passerelles USSD HTTP). Le chemin est soit fourni cumulé (text = « 1*2 »),
 * soit reconstitué à partir de la dernière saisie (input) et de la session enregistrée.
 */
@Service
public class UssdService {
    public static final String INVALID = "Choix invalide.";
    private final MenuRepo menus;
    private final UssdSessionRepo sessions;
    private final MenuActions actions;
    private final FraudService fraud;
    private final Clock clock;

    public UssdService(MenuRepo menus, UssdSessionRepo sessions, MenuActions actions, FraudService fraud, Clock clock) {
        this.menus = menus;
        this.sessions = sessions;
        this.actions = actions;
        this.fraud = fraud;
        this.clock = clock;
    }

    public record Reply(boolean end, String text) {
        public String wire() { return (end ? "END " : "CON ") + text; }
    }

    @Transactional
    public Reply handle(String sessionId, String rawMsisdn, String serviceCode, String text, String input) {
        String msisdn = Text.normalizeMsisdn(rawMsisdn);
        if (msisdn == null || sessionId == null || sessionId.isBlank()) return new Reply(true, MenuActions.UNAVAILABLE);
        var menu = menus.findByChannelAndCode(Channel.USSD, serviceCode == null ? "" : serviceCode.strip()).filter(m -> m.isActive()).orElse(null);
        if (menu == null) return new Reply(true, MenuActions.UNAVAILABLE);
        var svc = menu.getService();
        if (svc.getStatus() != ServiceStatus.ACTIVE || fraud.blocked(msisdn)) return new Reply(true, MenuActions.UNAVAILABLE);
        MenuTree tree = MenuTree.parse(menu.getDefinition(), true);
        var now = clock.instant();
        UssdSession s = sessions.findBySessionId(sessionId).orElseGet(() -> {
            var n = new UssdSession();
            n.setSessionId(sessionId);
            n.setMsisdn(msisdn);
            n.setOperator(svc.getShortCode().getOperator());
            n.setMenu(menu);
            n.setPath("");
            n.setStatus("OPEN");
            n.setStartedAt(now);
            return n;
        });
        if (!"OPEN".equals(s.getStatus())) return new Reply(true, MenuActions.UNAVAILABLE);   // session déjà terminée : renvoi tardif
        String path = text != null ? text.strip()
                : input == null || input.isBlank() ? s.getPath() : (s.getPath().isEmpty() ? input.strip() : s.getPath() + "*" + input.strip());
        MenuTree.Position pos = tree.at(path);
        s.setPath(pos.path());
        s.setUpdatedAt(now);
        Reply reply;
        if (!pos.node().leaf()) {
            reply = new Reply(false, pos.invalid() ? INVALID + "\n" + pos.node().text() : pos.node().text());
        } else {
            s.setStatus("ENDED");
            reply = new Reply(true, actions.execute(menu, pos.node(), Channel.USSD, s.getOperator(), msisdn, sessionId, pos.path()));
        }
        sessions.save(s);
        return reply;
    }
}
