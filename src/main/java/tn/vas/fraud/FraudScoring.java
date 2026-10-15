package tn.vas.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.config.FraudProperties;
import tn.vas.domain.FraudScore;
import tn.vas.repo.Repos.FraudScoreRepo;
import tn.vas.security.MsisdnCrypto;

/**
 * Scoring statistique du risque par numéro (0 à 100), recalculé périodiquement pour les numéros actifs dans l'heure. Chaque point du
 * score est expliqué (variables conservées avec le score) ; aucune boîte noire :
 * <ul>
 *   <li>volume : écart du nombre de MO de l'heure à la moyenne de la population active, en écarts-types (z-score) ;</li>
 *   <li>dispersion : nombre de services distincts sollicités dans l'heure ;</li>
 *   <li>dépense : part du plafond journalier déjà consommée ;</li>
 *   <li>sondage : part de MO refusés sur 24 h (mots-clés inconnus, bloqués, limites) ;</li>
 *   <li>horaire : part de l'activité entre 0 h et 5 h ;</li>
 *   <li>paiement : échecs de paiement sur facture (codes faux, refus) sur 24 h ;</li>
 *   <li>nouveauté : numéro jamais vu avant les dernières 24 h.</li>
 * </ul>
 * Score ≥ seuil d'alerte (FRAUD_SCORE_ALERT) → alerte ; ≥ seuil de blocage (FRAUD_SCORE_BLOCK) → blocage temporaire. Les seuils se
 * calibrent sur l'historique réel (3 mois conseillés) à partir de la distribution affichée dans l'écran Antifraude.
 */
@Service
public class FraudScoring {
    private static final Logger log = LoggerFactory.getLogger(FraudScoring.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REFUSED = List.of("UNKNOWN_KEYWORD", "BLOCKED", "FRAUD", "LIMIT_REACHED", "INVALID_CHOICE");
    private final JdbcTemplate jdbc;
    private final FraudScoreRepo scores;
    private final FraudService fraud;
    private final FraudProperties props;
    private final Clock clock;

    public FraudScoring(JdbcTemplate jdbc, FraudScoreRepo scores, FraudService fraud, FraudProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.scores = scores;
        this.fraud = fraud;
        this.props = props;
        this.clock = clock;
    }

    /** Variables d'un numéro (calculées en base) ; z = écart de volume en écarts-types. */
    public record Features(long mo1h, double z, long services1h, double spendRatio, long mo24h, long refused24h, long night24h, long dcbFailures24h, boolean newNumber) {}

    /** Score explicable : points par variable, plafonné à 100. */
    public static Map<String, Integer> points(Features f) {
        Map<String, Integer> p = new LinkedHashMap<>();
        if (f.z() >= 2) p.put("volume", (int) Math.min(35, Math.round(10 * (f.z() - 1))));
        if (f.services1h() >= 4) p.put("dispersion", (int) Math.min(20, 5 * (f.services1h() - 2)));
        if (f.spendRatio() >= 0.5) p.put("depense", (int) Math.min(25, Math.round(50 * (f.spendRatio() - 0.3))));
        if (f.mo24h() >= 5 && f.refused24h() >= 0.5 * f.mo24h()) p.put("sondage", (int) Math.min(35, Math.round(40.0 * f.refused24h() / f.mo24h())));
        if (f.mo24h() >= 5 && f.night24h() >= 0.7 * f.mo24h()) p.put("horaire", 10);
        if (f.dcbFailures24h() >= 2) p.put("paiement", (int) Math.min(30, 10 * f.dcbFailures24h()));
        if (f.newNumber() && !p.isEmpty()) p.put("nouveaute", 5);   // aggrave seulement un comportement déjà suspect
        return p;
    }

    public static int score(Map<String, Integer> points) {
        return Math.min(100, points.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Scheduled(cron = "${vas.fraud.score-cron:0 */5 * * * *}")
    @Transactional
    public int run() {
        if (!props.enabled()) return 0;
        Instant now = clock.instant();
        Timestamp h1 = Timestamp.from(now.minus(Duration.ofHours(1))), h24 = Timestamp.from(now.minus(Duration.ofHours(24)));
        Timestamp day = Timestamp.from(now.truncatedTo(ChronoUnit.DAYS));
        Map<String, Long> mo1h = new HashMap<>();
        jdbc.query("select msisdn, count(*) from mo_message where received_at >= ? group by msisdn", rs -> { mo1h.put(rs.getString(1), rs.getLong(2)); }, h1);
        if (mo1h.isEmpty()) return 0;
        double mean = mo1h.values().stream().mapToLong(Long::longValue).average().orElse(0);
        double sd = Math.sqrt(mo1h.values().stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
        double sdFloor = Math.max(sd, Math.max(1.0, mean * 0.5));   // population homogène ou réduite : pas de z-score artificiellement énorme
        Map<String, Long> services = toMap("select msisdn, count(distinct service_id) from mo_message where received_at >= ? and service_id is not null group by msisdn", h1);
        Map<String, BigDecimal> spend = new HashMap<>();
        jdbc.query("select msisdn, coalesce(sum(gross_amount),0) from ledger_event where created_at >= ? and billing_status not in ('REJECTED','REVERSED') group by msisdn",
                rs -> { spend.put(rs.getString(1), rs.getBigDecimal(2)); }, day);
        Map<String, Long> mo24 = toMap("select msisdn, count(*) from mo_message where received_at >= ? group by msisdn", h24);
        Map<String, Long> refused = toMap("select msisdn, count(*) from mo_message where received_at >= ? and outcome in ('" + String.join("','", REFUSED) + "') group by msisdn", h24);
        Map<String, Long> dcb = toMap("select msisdn, count(*) from dcb_transaction where created_at >= ? and status in ('FAILED','EXPIRED') group by msisdn", h24);
        Map<String, Long> older = toMap("select msisdn, count(*) from mo_message where received_at < ? group by msisdn", h24);
        ZoneId zone = clock.getZone();
        Map<String, Long> night = new HashMap<>();
        jdbc.query("select msisdn, received_at from mo_message where received_at >= ?", rs -> {
            int hour = rs.getTimestamp(2).toInstant().atZone(zone).getHour();
            if (hour < 5) night.merge(rs.getString(1), 1L, Long::sum);
        }, h24);
        double limit = props.dailySpendLimit() == null || props.dailySpendLimit().signum() <= 0 ? 0 : props.dailySpendLimit().doubleValue();
        int flagged = 0;
        for (var e : mo1h.entrySet()) {
            String stored = e.getKey();
            String msisdn = MsisdnCrypto.decrypt(stored);
            var f = new Features(e.getValue(), (e.getValue() - mean) / sdFloor, services.getOrDefault(stored, 0L),
                    limit == 0 ? 0 : spend.getOrDefault(stored, BigDecimal.ZERO).doubleValue() / limit, mo24.getOrDefault(stored, 0L),
                    refused.getOrDefault(stored, 0L), night.getOrDefault(stored, 0L), dcb.getOrDefault(stored, 0L), !older.containsKey(stored));
            var pts = points(f);
            int sc = score(pts);
            String decision = sc >= props.scoreBlock() ? "BLOCK" : sc >= props.scoreAlert() ? "ALERT" : "NONE";
            FraudScore row = scores.findByMsisdn(msisdn).orElseGet(FraudScore::new);
            row.setMsisdn(msisdn);
            row.setScore(sc);
            row.setFeatures(json(f, pts));
            row.setDecision(decision);
            row.setComputedAt(now);
            scores.save(row);
            if (!"NONE".equals(decision)) {
                flagged++;
                String detail = "score " + sc + " " + pts;
                if ("BLOCK".equals(decision)) fraud.report("RISK_SCORE", msisdn, null, detail);
                else fraud.raise("RISK_SCORE", msisdn, null, detail);
            }
        }
        if (flagged > 0) log.warn("scoring antifraude : {} numéro(s) au-dessus du seuil", flagged);
        return flagged;
    }

    private Map<String, Long> toMap(String sql, Timestamp from) {
        Map<String, Long> m = new HashMap<>();
        jdbc.query(sql, rs -> { m.put(rs.getString(1), rs.getLong(2)); }, from);
        return m;
    }

    private static String json(Features f, Map<String, Integer> pts) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("points", pts);
            m.put("mo1h", f.mo1h()); m.put("z", Math.round(f.z() * 10) / 10.0); m.put("services1h", f.services1h());
            m.put("spendRatio", Math.round(f.spendRatio() * 100) / 100.0); m.put("mo24h", f.mo24h()); m.put("refused24h", f.refused24h());
            m.put("night24h", f.night24h()); m.put("dcbFailures24h", f.dcbFailures24h()); m.put("newNumber", f.newNumber());
            String s = JSON.writeValueAsString(m);
            return s.length() > 1000 ? s.substring(0, 1000) : s;
        } catch (Exception e) {
            return "{}";
        }
    }
}
