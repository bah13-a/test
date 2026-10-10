package tn.vas.event;

import java.math.BigDecimal;
import java.time.Instant;
import tn.vas.domain.Enums.BillingStatus;
import tn.vas.domain.Enums.MtStatus;

/**
 * Événements du côté commande (CQRS). Publiés par les services d'écriture ; consommés APRÈS validation de la transaction par les
 * projections qui maintiennent les modèles de lecture. Le côté commande n'attend jamais la projection.
 */
public final class DomainEvents {
    private DomainEvents() {}

    /** Un MO a été enregistré avec son issue finale. serviceId null = aucun service. */
    public record MoRecorded(Instant receivedAt, long operatorId, Long serviceId, String outcome) {}

    /** Un MT change de statut. from == null à la création. createdAt = date de création du MT (clé du seau horaire). */
    public record MtTransitioned(Instant createdAt, long operatorId, Long serviceId, MtStatus from, MtStatus to) {}

    /** Un événement de facturation est créé (from == null) ou change de statut. createdAt = date de création de l'événement. */
    public record LedgerChanged(Instant createdAt, Long serviceId, BillingStatus from, BillingStatus to,
                                BigDecimal gross, BigDecimal partnerShare, BigDecimal providerShare) {}
}
