package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "billing_period")
@Getter
@Setter
public class BillingPeriod {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Instant fromAt;
    private Instant toAt;
    private Instant closedAt;
    private String closedBy;
    private int events;
    private BigDecimal gross;
    private BigDecimal operatorShare;
    private BigDecimal partnerShare;
    private BigDecimal providerShare;
    private BigDecimal taxes;
}
