package tn.vas.dcb;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.config.DcbProperties;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.fraud.FraudService;
import tn.vas.repo.Repos.*;
import tn.vas.service.*;

/**
 * Paiement sur facture opérateur (Direct Carrier Billing) en deux temps : le partenaire demande un paiement (montant = tarif DCB approuvé
 * du service, jamais un montant libre), l'abonné reçoit un code à usage unique par SMS, le partenaire confirme avec ce code ; l'API de
 * facturation de l'opérateur est alors appelée. Code haché, durée de vie et nombre d'essais limités, plafond de dépense antifraude.
 * Statuts : OTP_SENT → CHARGED | FAILED | EXPIRED ; CHARGED → REFUNDED.
 */
@Service
public class DcbService {
    private static final SecureRandom RNG = new SecureRandom();
    private final DcbRepo txs;
    private final TariffRepo tariffs;
    private final RoutingService routing;
    private final MtService mt;
    private final LedgerService ledger;
    private final ChargingGateway gateway;
    private final FraudService fraud;
    private final AuditService audit;
    private final DcbProperties props;
    private final Clock clock;

    public DcbService(DcbRepo txs, TariffRepo tariffs, RoutingService routing, MtService mt, LedgerService ledger, ChargingGateway gateway,
                      FraudService fraud, AuditService audit, DcbProperties props, Clock clock) {
        this.txs = txs;
        this.tariffs = tariffs;
        this.routing = routing;
        this.mt = mt;
        this.ledger = ledger;
        this.gateway = gateway;
        this.fraud = fraud;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public DcbTransaction start(ApiClient client, VasService svc, String rawMsisdn, String clientRef, String description) {
        if ("disabled".equalsIgnoreCase(props.mode())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "paiement sur facture non activé");
        if (clientRef != null) {
            var dup = txs.findByApiClientIdAndClientRef(client.getId(), clientRef);
            if (dup.isPresent()) return dup.get();   // idempotent : même référence client = même transaction
        }
        String msisdn = Text.normalizeMsisdn(rawMsisdn);
        if (msisdn == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MSISDN invalide");
        if (svc.getStatus() != ServiceStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "service inactif");
        var tariff = tariffs.effective(svc, EventType.DCB, clock.instant()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "aucun tarif DCB approuvé pour ce service"));
        // opérateur de l'abonné (plages + portabilité) ; à défaut celui du short code du service (service mono-opérateur)
        Operator op = routing.resolve(msisdn).orElse(svc.getShortCode().getOperator());
        if (fraud.blocked(msisdn) || !fraud.canSpend(msisdn, svc, tariff.getGrossAmount()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "paiement refusé (antifraude)");
        String otp = String.format("%06d", RNG.nextInt(1_000_000));
        var now = clock.instant();
        var t = new DcbTransaction();
        t.setTxId(UUID.randomUUID().toString());
        t.setApiClient(client);
        t.setService(svc);
        t.setOperator(op);
        t.setMsisdn(msisdn);
        t.setAmount(tariff.getGrossAmount());
        t.setDescription(description == null ? null : description.length() > 200 ? description.substring(0, 200) : description);
        t.setClientRef(clientRef);
        t.setStatus("OTP_SENT");
        t.setOtpHash(hash(t.getTxId(), otp));
        t.setOtpExpiresAt(now.plus(Duration.ofSeconds(props.otpTtlSeconds())));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        t = txs.save(t);
        String text = String.format("Code %s : paiement de %s TND pour %s. Valable %d min. Ne le communiquez a personne.",
                otp, tariff.getGrossAmount().stripTrailingZeros().toPlainString(), svc.getName(), Math.max(1, props.otpTtlSeconds() / 60));
        mt.submit(new MtService.Request(op, svc, msisdn, svc.getShortCode().getNumber(), text, Priority.TRANSACTIONAL, null, null, null,
                client.getId(), Duration.ofSeconds(props.otpTtlSeconds())));
        return t;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)   // un code faux doit rester compté
    public DcbTransaction confirm(DcbTransaction t, String otp) {
        if (!"OTP_SENT".equals(t.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "transaction " + t.getStatus());
        var now = clock.instant();
        if (now.isAfter(t.getOtpExpiresAt())) {
            finish(t, "EXPIRED", "code expiré");
            return t;
        }
        t.setOtpAttempts(t.getOtpAttempts() + 1);
        if (otp == null || !MessageDigest.isEqual(t.getOtpHash().getBytes(StandardCharsets.UTF_8), hash(t.getTxId(), otp.strip()).getBytes(StandardCharsets.UTF_8))) {
            if (t.getOtpAttempts() >= props.otpMaxAttempts()) {
                finish(t, "FAILED", "trop d'essais de code");
                fraud.report(FraudService.OTP_ABUSE, t.getMsisdn(), t.getService(), "DCB : " + t.getOtpAttempts() + " codes faux (transaction " + t.getTxId() + ")");
                return t;
            }
            t.setUpdatedAt(now);
            txs.save(t);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "code incorrect");
        }
        var r = gateway.charge(t.getOperator(), t.getMsisdn(), t.getAmount(), t.getTxId(), t.getDescription());
        t.setOtpHash(null);   // code consommé
        if (!r.ok()) {
            finish(t, "FAILED", r.error());
            return t;
        }
        t.setProviderRef(r.providerRef());
        finish(t, "CHARGED", null);
        String eventId = "DCB-" + t.getTxId();
        var svc = t.getService();
        ledger.record(eventId, EventType.DCB, t.getOperator(), svc, svc.getShortCode().getNumber(), t.getMsisdn(), t.getTxId(), 1)
                .ifPresent(e -> ledger.transition(eventId, BillingStatus.CHARGED));
        return t;
    }

    @Transactional
    public DcbTransaction refund(DcbTransaction t, String actor) {
        if (!"CHARGED".equals(t.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "seul un paiement CHARGED est remboursable");
        var r = gateway.refund(t.getOperator(), t.getProviderRef(), t.getAmount(), t.getTxId());
        if (!r.ok()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "remboursement refusé par l'opérateur : " + r.error());
        finish(t, "REFUNDED", null);
        ledger.transition("DCB-" + t.getTxId(), BillingStatus.REVERSED);
        audit.log("DCB_REFUND", "dcb:" + t.getTxId(), actor);
        return t;
    }

    /** Codes non utilisés à échéance : EXPIRED (le partenaire voit l'état en consultant la transaction). */
    @Scheduled(fixedDelayString = "${vas.dcb.expire-delay-ms:60000}")
    @Transactional
    public int expire() {
        var list = txs.expired(clock.instant());
        list.forEach(t -> finish(t, "EXPIRED", "code expiré"));
        return list.size();
    }

    private void finish(DcbTransaction t, String status, String error) {
        t.setStatus(status);
        t.setError(error == null ? null : error.length() > 300 ? error.substring(0, 300) : error);
        if (!"OTP_SENT".equals(status)) t.setOtpHash(null);
        t.setUpdatedAt(clock.instant());
        txs.save(t);
    }

    static String hash(String txId, String otp) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((txId + ":" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
