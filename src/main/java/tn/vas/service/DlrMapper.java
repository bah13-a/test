package tn.vas.service;

import java.util.Locale;
import java.util.Set;
import tn.vas.domain.Enums.MtStatus;

/**
 * Mapping statut DLR brut → statut interne normalisé (le brut est toujours conservé).
 * Avec dlr-level=3, Jasmin envoie deux types d'événements (constaté sur Jasmin 0.10 réel) :
 *  - le statut du submit_sm_resp du SMSC : ESME_ROK (accepté) ou une erreur ESME_R* ;
 *  - le reçu de livraison SMPP : DELIVRD, UNDELIV, EXPIRED, REJECTD, ACCEPTD, ENROUTE, DELETED, UNKNOWN.
 */
public final class DlrMapper {
    private DlrMapper() {}

    /** Erreurs SMSC transitoires : Jasmin remet le message en file et réessaie, ce n'est PAS un échec de livraison. */
    private static final Set<String> TRANSIENT = Set.of("ESME_RTHROTTLED", "ESME_RMSGQFUL", "ESME_RSYSERR", "ESME_RX_T_APPN", "ESME_RX_R_APPN", "ESME_RX_P_APPN",
            "ESME_RBINDFAIL", "ESME_RUNKNOWNERR");

    /** @return le statut normalisé, ou null si l'événement ne doit pas modifier l'état (erreur SMSC transitoire, réessayée par Jasmin). */
    public static MtStatus map(String raw) {
        if (raw == null) return MtStatus.UNKNOWN;
        String r = raw.trim().toUpperCase(Locale.ROOT);
        if (r.equals("ESME_ROK")) return MtStatus.SUBMITTED;
        if (r.startsWith("ESME_")) return TRANSIENT.contains(r) ? null : MtStatus.REJECTED; // ex. ESME_RINVDSTADR : refus définitif
        return switch (r) {
            case "ACCEPTD", "ENROUTE" -> MtStatus.SUBMITTED;
            case "DELIVRD" -> MtStatus.DELIVERED;
            case "EXPIRED" -> MtStatus.EXPIRED;
            case "UNDELIV", "DELETED" -> MtStatus.UNDELIVERABLE;
            case "REJECTD" -> MtStatus.REJECTED;
            default -> MtStatus.UNKNOWN;
        };
    }

    public static boolean isFinal(MtStatus s) {
        return s == MtStatus.DELIVERED || s == MtStatus.EXPIRED || s == MtStatus.UNDELIVERABLE
                || s == MtStatus.REJECTED || s == MtStatus.FAILED;
    }
}
