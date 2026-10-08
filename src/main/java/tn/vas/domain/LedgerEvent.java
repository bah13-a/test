package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "ledger_event")
@Getter
@Setter
public class LedgerEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String eventId;
    @Enumerated(EnumType.STRING) private EventType eventType;
    @ManyToOne(optional=false) private Operator operator;
    @ManyToOne private VasService service;
    private String shortCode;
    private String msisdn;
    private BigDecimal grossAmount;
    private BigDecimal operatorShare;
    private BigDecimal providerShare;
    private BigDecimal partnerShare;
    private BigDecimal taxes;
    @Enumerated(EnumType.STRING) private BillingStatus billingStatus;
    private String sourceReference;
    private Instant createdAt;
    private Instant updatedAt;
}
