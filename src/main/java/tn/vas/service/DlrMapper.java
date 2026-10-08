package tn.vas.service;

import java.util.Locale;
import tn.vas.domain.Enums.MtStatus;

/** Mapping statut DLR brut opérateur → statut interne normalisé (le brut est toujours conservé). */
public final class DlrMapper {
    private DlrMapper() {}

    public static MtStatus map(String raw) {
        if (raw == null) return MtStatus.UNKNOWN;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
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
