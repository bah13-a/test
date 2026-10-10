package tn.vas.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

/** Statistiques temps réel, résultats officiels et clôture d'une campagne (service) : vote, quiz, contenu premium ou générique. */
@Service
public class CampaignService {
    private final JdbcTemplate jdbc;
    private final MoRepo mos;
    private final MtRepo mts;
    private final LedgerRepo ledger;
    private final ServiceRepo services;
    private final VoteOptionRepo options;
    private final VoteBallotRepo ballots;
    private final QuizProgressRepo quiz;
    private final QuizQuestionRepo questions;
    private final ContentItemRepo items;
    private final ContentTokenRepo tokens;
    private final AuditService audit;
    private final Clock clock;

    public CampaignService(JdbcTemplate jdbc, MoRepo mos, MtRepo mts, LedgerRepo ledger, ServiceRepo services, VoteOptionRepo options, VoteBallotRepo ballots,
                           QuizProgressRepo quiz, QuizQuestionRepo questions, ContentItemRepo items, ContentTokenRepo tokens, AuditService audit, Clock clock) {
        this.jdbc = jdbc; this.mos = mos; this.mts = mts; this.ledger = ledger; this.services = services; this.options = options; this.ballots = ballots;
        this.quiz = quiz; this.questions = questions; this.items = items; this.tokens = tokens; this.audit = audit; this.clock = clock;
    }

    /** Tableau de bord d'une campagne. Les montants ne sont inclus que si {@code finance} (rôles financiers). */
    public Map<String, Object> stats(VasService svc, int hours, boolean finance) {
        Instant from = clock.instant().minus(Duration.ofHours(hours));
        Map<String, Long> mo = new TreeMap<>(), mt = new TreeMap<>();
        mos.countByOutcome(svc).forEach(o -> mo.put(o[0].toString(), (Long) o[1]));
        mts.countByStatus(svc).forEach(o -> mt.put(o[0].toString(), (Long) o[1]));
        Long participants = jdbc.queryForObject("select count(distinct msisdn) from mo_message where service_id = ?", Long.class, svc.getId());
        List<Map<String, Object>> series = jdbc.query(
                "select date_trunc('hour', received_at) as h, count(*) as n, count(distinct msisdn) as u from mo_message where service_id = ? and received_at >= ? group by h order by h",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("hour", rs.getTimestamp("h").toInstant().toString());
                    m.put("mo", rs.getLong("n"));
                    m.put("participants", rs.getLong("u"));
                    return m;
                }, svc.getId(), Timestamp.from(from));
        long delivered = mt.getOrDefault("DELIVERED", 0L);
        long finals = delivered + mt.getOrDefault("UNDELIVERABLE", 0L) + mt.getOrDefault("EXPIRED", 0L) + mt.getOrDefault("REJECTED", 0L);
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
        out.put("deliveryRate", finals == 0 ? 0.0 : (double) delivered / finals);
        out.put("series", series);
        if (finance) {
            Map<String, Object> bill = new TreeMap<>();
            ledger.totalsByStatus(List.of(svc)).forEach(o -> bill.put(o[0].toString(), Map.of("gross", o[1], "partner", o[2], "provider", o[3], "events", o[4])));
            out.put("billing", bill);
        }
        return out;
    }

    /** Résultats lisibles (lignes « libellé / nombre / détail ») selon le type de service ; msisdn toujours masqués. */
    public List<Map<String, Object>> results(VasService svc) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (svc.getType()) {
            case VOTE -> {
                var opts = options.findByServiceOrderById(svc);
                if (opts.isEmpty()) {
                    Map<String, Long> merged = new TreeMap<>();
                    for (Object[] o : mos.resultsByContent(svc)) merged.merge(String.valueOf(o[0]).trim().toUpperCase(Locale.ROOT), (Long) o[1], Long::sum);
                    merged.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(e -> rows.add(row(e.getKey(), e.getValue(), null)));
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
                for (var p : quiz.findTop50ByServiceAndStatusOrderByScoreDescCompletedAtAsc(svc, "COMPLETED")) rows.add(row("#" + rank++ + " " + mask(p.getMsisdn()), p.getScore(), "/" + max));
            }
            case PREMIUM_CONTENT -> {
                for (var it : items.findByServiceOrderById(svc)) {
                    long issued = jdbc.queryForObject("select count(*) from content_token where item_id = ?", Long.class, it.getId());
                    long used = jdbc.queryForObject("select coalesce(sum(uses), 0) from content_token where item_id = ?", Long.class, it.getId());
                    rows.add(row(it.getCode() + " — " + it.getTitle(), issued, "ouvertures : " + used));
                }
            }
            default -> {
                Map<String, Long> merged = new TreeMap<>();
                for (Object[] o : mos.resultsByContent(svc)) merged.merge(String.valueOf(o[0]).trim().toUpperCase(Locale.ROOT), (Long) o[1], Long::sum);
                merged.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(e -> rows.add(row(e.getKey(), e.getValue(), null)));
            }
        }
        return rows;
    }

    /** Clôture : plus aucune participation, date de clôture figée, résultats officiels retournés (et exportables). */
    @Transactional
    public Map<String, Object> close(VasService svc) {
        if (svc.getStatus() == ServiceStatus.CLOSED) throw new IllegalStateException("campagne déjà clôturée");
        svc.setStatus(ServiceStatus.CLOSED);
        svc.setClosedAt(clock.instant());
        services.save(svc);
        audit.log("CAMPAIGN_CLOSE", "service:" + svc.getId(), svc.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceId", svc.getId());
        out.put("closedAt", svc.getClosedAt());
        out.put("results", results(svc));
        return out;
    }

    private static Map<String, Object> row(String label, long count, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", label);
        m.put("count", count);
        m.put("detail", detail == null ? "" : detail);
        return m;
    }

    static String mask(String msisdn) {
        return msisdn == null || msisdn.length() < 6 ? msisdn : msisdn.substring(0, msisdn.length() - 5) + "*****";
    }
}
