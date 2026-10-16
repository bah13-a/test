package tn.vas.smpp;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Format de date SMPP v3.4 (§7.1.1) : absolu « YYMMDDhhmmsstnnp » (t = dixièmes de seconde, nn = écart à UTC en quarts d'heure,
 * p = « + » ou « - ») ou relatif « YYMMDDhhmmss000R » (durée ajoutée à l'heure courante). Chaîne vide = non renseigné.
 */
public final class SmppTime {
    private SmppTime() {}

    /** @return l'instant désigné, null si vide ; IllegalArgumentException si le format est invalide. */
    public static Instant parse(String v, Instant now) {
        if (v == null || v.isBlank()) return null;
        if (!v.matches("\\d{12}\\d\\d{2}[+\\-R]")) throw new IllegalArgumentException("date SMPP invalide : " + v);
        int yy = n(v, 0), mo = n(v, 2), dd = n(v, 4), hh = n(v, 6), mi = n(v, 8), ss = n(v, 10);
        char p = v.charAt(15);
        if (p == 'R') {
            return now.atZone(ZoneOffset.UTC).plusYears(yy).plusMonths(mo).plusDays(dd).plusHours(hh).plusMinutes(mi).plusSeconds(ss).toInstant();
        }
        int tenths = v.charAt(12) - '0', quarters = n(v, 13);
        if (quarters > 48) throw new IllegalArgumentException("date SMPP invalide : " + v);
        LocalDateTime local;
        try {
            local = LocalDateTime.of(2000 + yy, mo, dd, hh, mi, ss).plusNanos(tenths * 100_000_000L);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("date SMPP invalide : " + v);
        }
        int offsetMinutes = quarters * 15 * (p == '-' ? -1 : 1);
        return local.toInstant(ZoneOffset.ofTotalSeconds(offsetMinutes * 60));
    }

    /** Date absolue en UTC (« 000+ »), pour final_date de query_sm_resp. */
    public static String format(Instant t) {
        return t == null ? null : DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneOffset.UTC).format(t) + "000+";
    }

    public static Duration until(Instant from, Instant to) {
        return Duration.between(from, to);
    }

    private static int n(String v, int i) {
        return Integer.parseInt(v.substring(i, i + 2));
    }
}
