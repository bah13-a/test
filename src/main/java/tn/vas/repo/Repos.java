package tn.vas.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.vas.domain.*;

public final class Repos {
    private Repos() {}

    public interface OperatorRepo extends JpaRepository<Operator, Long> {
        Optional<Operator> findByCode(String code);
        Optional<Operator> findByJasminConnector(String connector);
    }

    public interface ShortCodeRepo extends JpaRepository<ShortCode, Long> {
        Optional<ShortCode> findByNumberAndOperator(String number, Operator operator);
    }

    public interface PartnerRepo extends JpaRepository<Partner, Long> {}

    public interface ServiceRepo extends JpaRepository<VasService, Long> {
        List<VasService> findByPartner(Partner partner);
    }

    public interface KeywordRepo extends JpaRepository<Keyword, Long> {
        // mots-clés stockés en majuscules (Locale.ROOT) : comparaison exacte, indépendante de la locale de la base (pas de upper() SQL)
        @Query("select k from Keyword k where k.service.shortCode = :sc and k.word = :word")
        List<Keyword> findByShortCodeAndWord(@Param("sc") ShortCode sc, @Param("word") String word);
        List<Keyword> findByService(VasService service);
        @Query("select k from Keyword k where k.service.shortCode = :sc order by k.id")
        List<Keyword> findByShortCode(@Param("sc") ShortCode sc);
    }

    public interface TariffRepo extends JpaRepository<Tariff, Long> {
        @Query("select t from Tariff t where t.service = :s and t.eventType = :e and t.approved = true "
                + "and t.effectiveFrom <= :at order by t.effectiveFrom desc")
        List<Tariff> effective(@Param("s") VasService s, @Param("e") Enums.EventType e, @Param("at") Instant at);
    }

    public interface MoRepo extends JpaRepository<MoMessage, Long> {
        boolean existsByOperatorAndDedupKey(Operator o, String key);
        boolean existsByOperatorAndMsisdnAndShortCodeAndContentAndReceivedAtAfter(Operator o, String msisdn, String shortCode, String content, Instant after);
        long countByServiceAndMsisdnAndOutcome(VasService s, String msisdn, Enums.MoOutcome outcome);
        @Query("select m.outcome, count(m) from MoMessage m where m.service = :s group by m.outcome")
        List<Object[]> countByOutcome(@Param("s") VasService s);
        @Query("select m.content, count(m) from MoMessage m where m.service = :s and m.outcome = tn.vas.domain.Enums.MoOutcome.ROUTED group by m.content")
        List<Object[]> resultsByContent(@Param("s") VasService s);
        @Query("select m.outcome, count(m) from MoMessage m where m.receivedAt >= :from group by m.outcome")
        List<Object[]> countByOutcomeSince(@Param("from") Instant from);
    }

    public interface MtRepo extends JpaRepository<MtMessage, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<MtMessage> {
        Optional<MtMessage> findByCorrelationId(String id);
        long countByStatus(Enums.MtStatus status);
        @Query("select m from MtMessage m where m.status = :s and m.scheduledAt is not null and m.scheduledAt <= :now")
        List<MtMessage> findDueScheduled(@Param("s") Enums.MtStatus s, @Param("now") Instant now);
        Optional<MtMessage> findByApiClientIdAndClientRef(Long apiClientId, String clientRef);
        List<MtMessage> findTop100ByOrderByCreatedAtDesc();
        List<MtMessage> findTop100ByMsisdnOrderByCreatedAtDesc(String msisdn);
        Optional<MtMessage> findBySmscMessageId(String id);
        List<MtMessage> findByStatusAndCreatedAtBefore(Enums.MtStatus s, Instant before);
        List<MtMessage> findTop500ByStatusAndUpdatedAtBefore(Enums.MtStatus s, Instant before);
        @Query("select m.status, count(m) from MtMessage m where m.service = :s group by m.status")
        List<Object[]> countByStatus(@Param("s") VasService s);
        @Query("select m.status, count(m) from MtMessage m where m.createdAt >= :from group by m.status")
        List<Object[]> countByStatusSince(@Param("from") Instant from);
    }

    public interface MtHistoryRepo extends JpaRepository<MtStatusHistory, Long> {
        List<MtStatusHistory> findByMtIdOrderByAtAsc(Long mtId);
    }

    public interface SubscriptionRepo extends JpaRepository<Subscription, Long> {
        Optional<Subscription> findByMsisdnAndService(String msisdn, VasService service);
        @Query("select s from Subscription s where s.msisdn = :m and s.service.shortCode = :sc and s.status = :st")
        List<Subscription> findByMsisdnShortCodeStatus(@Param("m") String m, @Param("sc") ShortCode sc, @Param("st") Enums.SubStatus st);
        List<Subscription> findByStatusAndNextRenewalAtBefore(Enums.SubStatus s, Instant before);
    }

    public interface ConsentRepo extends JpaRepository<ConsentRecord, Long> {
        List<ConsentRecord> findByMsisdnAndServiceOrderByAtAsc(String msisdn, VasService s);
    }

    public interface LedgerRepo extends JpaRepository<LedgerEvent, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<LedgerEvent> {
        Optional<LedgerEvent> findByEventId(String eventId);
        List<LedgerEvent> findByOperatorAndCreatedAtBetween(Operator o, Instant from, Instant to);
        @Query("select e.billingStatus, sum(e.grossAmount), sum(e.partnerShare), sum(e.providerShare), count(e) from LedgerEvent e "
                + "where e.service in :svcs group by e.billingStatus")
        List<Object[]> totalsByStatus(@Param("svcs") List<VasService> svcs);
        @Query("select e.billingStatus, sum(e.grossAmount), sum(e.partnerShare), sum(e.providerShare), count(e) from LedgerEvent e "
                + "where e.createdAt >= :from group by e.billingStatus")
        List<Object[]> totalsSince(@Param("from") Instant from);
        @Query("select count(e) from LedgerEvent e where e.createdAt >= :from and e.createdAt < :to and e.billingStatus in :sts")
        long countInPeriod(@Param("from") Instant from, @Param("to") Instant to, @Param("sts") List<Enums.BillingStatus> sts);
        @Query("select count(e), coalesce(sum(e.grossAmount),0), coalesce(sum(e.operatorShare),0), coalesce(sum(e.partnerShare),0), coalesce(sum(e.providerShare),0), coalesce(sum(e.taxes),0) "
                + "from LedgerEvent e where e.createdAt >= :from and e.createdAt < :to and e.billingStatus = 'CHARGED'")
        List<Object[]> chargedTotals(@Param("from") Instant from, @Param("to") Instant to);
        @Query("select e.service, e.billingStatus, count(e), coalesce(sum(e.grossAmount),0), coalesce(sum(e.partnerShare),0) from LedgerEvent e "
                + "where e.service in :svcs and e.createdAt >= :from and e.createdAt < :to group by e.service, e.billingStatus")
        List<Object[]> statement(@Param("svcs") List<VasService> svcs, @Param("from") Instant from, @Param("to") Instant to);
        @Query("select count(e) from LedgerEvent e where e.service in :svcs and e.createdAt >= :from and e.createdAt < :to and e.billingStatus in :sts")
        long countForServices(@Param("svcs") List<VasService> svcs, @Param("from") Instant from, @Param("to") Instant to, @Param("sts") List<Enums.BillingStatus> sts);
    }

    public interface BillingPeriodRepo extends JpaRepository<BillingPeriod, Long> {
        @Query("select count(p) > 0 from BillingPeriod p where p.fromAt <= :at and p.toAt > :at")
        boolean closedAt(@Param("at") Instant at);
        @Query("select count(p) > 0 from BillingPeriod p where p.fromAt < :to and p.toAt > :from")
        boolean overlaps(@Param("from") Instant from, @Param("to") Instant to);
        List<BillingPeriod> findAllByOrderByFromAtDesc();
    }

    public interface PayoutRepo extends JpaRepository<PartnerPayout, Long> {
        @Query("select count(p) > 0 from PartnerPayout p where p.partner = :pa and p.status <> 'CANCELLED' and p.fromAt < :to and p.toAt > :from")
        boolean overlaps(@Param("pa") Partner pa, @Param("from") Instant from, @Param("to") Instant to);
        List<PartnerPayout> findByPartnerOrderByFromAtDesc(Partner p);
        List<PartnerPayout> findAllByOrderByIdDesc();
    }

    public interface ReconRepo extends JpaRepository<ReconItem, Long> {
        List<ReconItem> findByBatchId(String batchId);
        org.springframework.data.domain.Page<ReconItem> findByBatchId(String batchId, org.springframework.data.domain.Pageable p);
        org.springframework.data.domain.Page<ReconItem> findByBatchIdAndResult(String batchId, Enums.ReconResult r, org.springframework.data.domain.Pageable p);
    }

    public interface ApiClientRepo extends JpaRepository<ApiClient, Long> {
        Optional<ApiClient> findByKeyHashAndActiveTrue(String hash);
    }

    public interface AuditRepo extends JpaRepository<AuditLog, Long> {}

    public interface WebhookRepo extends JpaRepository<WebhookOutbox, Long> {
        List<WebhookOutbox> findByStatusAndNextAttemptAtBefore(String status, Instant before);
        boolean existsByEventId(String eventId);
    }

    public interface UserRepo extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByUsername(String username);
    }

    public interface ReplyRepo extends JpaRepository<ServiceReply, Long> {
        Optional<ServiceReply> findByServiceAndLangAndKind(VasService s, String lang, String kind);
        List<ServiceReply> findByService(VasService s);
    }

    public interface RuleRepo extends JpaRepository<MsisdnRule, Long> {
        @Query("select count(r) > 0 from MsisdnRule r where r.ruleType = 'BLACK' and r.msisdn = :m and (r.service is null or r.service = :s)")
        boolean blacklisted(@Param("m") String msisdn, @Param("s") VasService s);
        @Query("select count(r) from MsisdnRule r where r.ruleType = 'WHITE' and (r.service is null or r.service = :s)")
        long whitelistSize(@Param("s") VasService s);
        @Query("select count(r) > 0 from MsisdnRule r where r.ruleType = 'WHITE' and r.msisdn = :m and (r.service is null or r.service = :s)")
        boolean whitelisted(@Param("m") String msisdn, @Param("s") VasService s);
    }

    public interface QuizQuestionRepo extends JpaRepository<QuizQuestion, Long> {
        List<QuizQuestion> findByServiceOrderByPositionAsc(VasService s);
        Optional<QuizQuestion> findByServiceAndPosition(VasService s, int position);
        long countByService(VasService s);
    }

    public interface QuizProgressRepo extends JpaRepository<QuizProgress, Long> {
        Optional<QuizProgress> findByServiceAndMsisdn(VasService s, String msisdn);
        @Query("select p from QuizProgress p where p.msisdn = :m and p.status = 'IN_PROGRESS' and p.service.shortCode = :sc")
        List<QuizProgress> activeFor(@Param("m") String msisdn, @Param("sc") ShortCode sc);
        List<QuizProgress> findTop50ByServiceAndStatusOrderByScoreDescCompletedAtAsc(VasService s, String status);
        long countByServiceAndStatus(VasService s, String status);
    }

    public interface ContentItemRepo extends JpaRepository<ContentItem, Long> {
        List<ContentItem> findByServiceOrderById(VasService s);
        Optional<ContentItem> findByServiceAndCode(VasService s, String code);
    }

    public interface ContentTokenRepo extends JpaRepository<ContentToken, Long> {
        Optional<ContentToken> findByToken(String token);
        long countByItem_Service(VasService s);
    }

    public interface VoteOptionRepo extends JpaRepository<VoteOption, Long> {
        List<VoteOption> findByServiceOrderById(VasService s);
        Optional<VoteOption> findByServiceAndCodeIgnoreCase(VasService s, String code);
    }

    public interface VoteBallotRepo extends JpaRepository<VoteBallot, Long> {
        long countByServiceAndMsisdn(VasService s, String msisdn);
        @Query("select b.optionCode, count(b), count(distinct b.msisdn) from VoteBallot b where b.service = :s group by b.optionCode")
        List<Object[]> tally(@Param("s") VasService s);
        @Query("select count(distinct b.msisdn) from VoteBallot b where b.service = :s")
        long distinctVoters(@Param("s") VasService s);
    }
}
