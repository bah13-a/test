package tn.vas.channel;

import java.util.Set;
import org.springframework.stereotype.Component;
import tn.vas.domain.Enums.BillingStatus;
import tn.vas.domain.Enums.Channel;
import tn.vas.domain.Enums.EventType;
import tn.vas.domain.Enums.MoOutcome;
import tn.vas.domain.MenuDefinition;
import tn.vas.domain.Operator;
import tn.vas.fraud.FraudService;
import tn.vas.service.LedgerService;
import tn.vas.service.MoService;

/** Exécution des feuilles de menu USSD / IVR (KEYWORD, CHARGE) : mêmes moteurs, mêmes règles et même facturation que le SMS. */
@Component
public class MenuActions {
    public static final String UNAVAILABLE = "Service momentanément indisponible.";
    private static final Set<MoOutcome> REFUSED = Set.of(MoOutcome.FRAUD, MoOutcome.BLOCKED, MoOutcome.SERVICE_CLOSED, MoOutcome.LIMIT_REACHED);
    private final MoService mo;
    private final LedgerService ledger;
    private final FraudService fraud;

    public MenuActions(MoService mo, LedgerService ledger, FraudService fraud) {
        this.mo = mo;
        this.ledger = ledger;
        this.fraud = fraud;
    }

    /**
     * Exécute la feuille atteinte et renvoie le texte à afficher / prononcer. ref identifie la session ou l'appel :
     * une même feuille rejouée (renvoi de la passerelle) ne produit ni second MO ni seconde facturation.
     */
    public String execute(MenuDefinition menu, MenuTree.Node leaf, Channel channel, Operator op, String msisdn, String ref, String path) {
        var svc = menu.getService();
        String shortCode = svc.getShortCode().getNumber();
        switch (leaf.action()) {
            case KEYWORD -> {
                MoOutcome o = mo.handle(op.getCode(), msisdn, shortCode, leaf.param(), channel.name().toLowerCase() + "-" + ref + "-" + path, channel);
                return REFUSED.contains(o) ? UNAVAILABLE : leaf.text();
            }
            case CHARGE -> {
                EventType type = channel == Channel.IVR ? EventType.IVR : EventType.USSD;
                String eventId = channel.name() + "-" + ref + "-" + path;
                if (!fraud.canSpendFor(msisdn, svc, type)) return UNAVAILABLE;
                // l'opérateur valorise la session USSD / l'appel surtaxé de son côté : l'événement est imputé immédiatement, confirmé au rapprochement
                ledger.record(eventId, type, op, svc, shortCode, msisdn, ref, 1).ifPresent(e -> ledger.transition(eventId, BillingStatus.CHARGED));
                return leaf.text();
            }
            default -> { return leaf.text(); }
        }
    }
}
