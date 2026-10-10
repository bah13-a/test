package tn.vas.query;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.repo.Repos.*;
import tn.vas.support.Views;

/** Côté requête : statistiques temps réel (modèles de lecture) et résultats officiels d'une campagne : vote, quiz, contenu premium ou générique. */
@Service
@Transactional(readOnly = true)
public class CampaignQueries {
    private final JdbcTemplate jdbc;
    private final MoRepo mos;
    private final VoteOptionRepo options;
    private final VoteBallotRepo ballots;
    private final QuizProgressRepo quiz;
    private final QuizQuestionRepo questions;
    private final ContentItemRepo items;
    private final ReportingQueries reporting;
    private final Clock clock;

    public CampaignQueries(JdbcTemplate jdbc, MoRepo mos, VoteOptionRepo options, VoteBallotRepo ballots, QuizProgressRepo quiz, QuizQuestionRepo questions,
                           ContentItemRepo items, ReportingQueries reporting, Clock clock) {
        this.jdbc = jdbc; this.mos = mos; this.options = options; this.ballots = ballots; this.quiz = quiz; this.questions = questions;
        this.items = items; this.reporting = reporting; this.clock = clock;
    }

    /** Compteurs cumulés d'un service (MO par issue, MT par statut courant). */
    public Map<String, Map<String, Long>> counts(Long serviceId) {
        var all = new Timestamp(0);
        return Map.of("mo", reporting.traffic("MO", all, serviceId), "mt", reporting.traffic("MT", all, serviceId));
    }

    /** Tableau de bord d'une campagne. Les montants ne sont inclus que si {@code finance} (rôles financiers). */
    public Map<String, Object> stats(VasService svc, int hours, boolean finance) {
        var from = ReportingQueries.since(clock.instant(), hours);
        var c = counts(svc.getId());
        Map<String, Long> mo = c.get("mo"), mt = c.get("mt");
        Long participants = jdbc.queryForObject("select count(distinct msisdn) from mo_message where service_id = ?", Long.class, svc.getId());
        List<Map<String, Object>> series = jdbc.query(
                "select date_trunc('hour', received_at) as h, count(*) as n, count(distinct msisdn) as u from mo_message where service_id = ? and received_at >= ? group by h order by h",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("hour", rs.getTimestamp("h").toInstant().toString());
                    m.put("mo", rs.getLong("n"));
                    m.put("participants", rs.getLong("u"));
                    return m;
                }, svc.getId(), from);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceId", svc.getId());
        out.put("name", svc.getName());
        out.put("type", svc.getType());
        out.put("status", svc.getStatus());
        out.put("closedAt", svc.getClosedAt());
        out.put("hours", hours);
        out.put("mo", mo);
        out.put("mt", mt);
        out.put("participants", participants);
        out.put("deliveryRate", ReportingQueries.deliveryRate(mt));
        out.put("series", series);
        if (finance) out.put("billing", reporting.billing(new Timestamp(0), List.of(svc.getId())));
        return out;
    }

    /** Résultats lisibles (lignes « libellé / nombre / détail ») selon le type de service ; msisdn toujours masqués. */
    public List<Map<String, Object>> results(VasService svc) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (svc.getType()) {
            case VOTE -> {
                var opts = options.findByServiceOrderById(svc);
                if (opts.isEmpty()) {
                    contentRows(svc, rows);
                } else {
                    Map<String, long[]> t = new HashMap<>();
                    ballots.tally(svc).forEach(o -> t.put(String.valueOf(o[0]), new long[]{(Long) o[1], (Long) o[2]}));
                    long total = t.values().stream().mapToLong(a -> a[0]).sum();
                    opts.stream().map(o -> { long n = t.getOrDefault(o.getCode(), new long[]{0, 0})[0]; return row(o.getCode() + " — " + o.getLabel(), n, total == 0 ? "0 %" : String.format("%.1f %%", 100.0 * n / total)); })
                            .sorted((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count"))).forEach(rows::add);
                    rows.add(row("Votants uniques", ballots.distinctVoters(svc), "bulletins : " + total));
                }
            }
            case QUIZ -> {
                long done = quiz.countByServiceAndStatus(svc, "COMPLETED"), running = quiz.countByServiceAndStatus(svc, "IN_PROGRESS");
                rows.add(row("Parties terminées", done, null));
                rows.add(row("Parties en cours", running, null));
                int max = questions.findByServiceOrderByPositionAsc(svc).stream().mapToInt(QuizQuestion::getPoints).sum();
                int rank = 1;
                for (var p : quiz.findTop50ByServiceAndStatusOrderByScoreDescCompletedAtAsc(svc, "COMPLETED")) rows.add(row("#" + rank++ + " " + Views.mask(p.getMsisdn()), p.getScore(), "/" + max));
            }
            case PREMIUM_CONTENT -> {
                for (var it : items.findByServiceOrderById(svc)) {
                    long issued = jdbc.queryForObject("select count(*) from content_token where item_id = ?", Long.class, it.getId());
                    long used = jdbc.queryForObject("select coalesce(sum(uses), 0) from content_token where item_id = ?", Long.class, it.getId());
                    rows.add(row(it.getCode() + " — " + it.getTitle(), issued, "ouvertures : " + used));
                }
            }
            default -> contentRows(svc, rows);
        }
        return rows;
    }

    private void contentRows(VasService svc, List<Map<String, Object>> rows) {
        Map<String, Long> merged = new TreeMap<>();
        for (Object[] o : mos.resultsByContent(svc)) merged.merge(String.valueOf(o[0]).trim().toUpperCase(Locale.ROOT), (Long) o[1], Long::sum);
        merged.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(e -> rows.add(row(e.getKey(), e.getValue(), null)));
    }

    private static Map<String, Object> row(String label, long count, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", label);
        m.put("count", count);
        m.put("detail", detail == null ? "" : detail);
        return m;
    }
}
