package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "partner_payout")
@Getter
@Setter
public class PartnerPayout {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private Partner partner;
    private Instant fromAt;
    private Instant toAt;
    private BigDecimal amount;
    private String status;
    private Instant createdAt;
    private String createdBy;
    private Instant paidAt;
    private String paidBy;
    private String reference;
}
