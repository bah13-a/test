package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Paiement sur facture opérateur : OTP_SENT → CHARGED | FAILED | EXPIRED ; CHARGED → REFUNDED. */
@Entity
@Table(name = "dcb_transaction")
@Getter
@Setter
public class DcbTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String txId;
    @ManyToOne(optional = false) private ApiClient apiClient;
    @ManyToOne(optional = false) private VasService service;
    @ManyToOne(optional = false) private Operator operator;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    private BigDecimal amount;
    private String description;
    private String status;
    private String otpHash;
    private Instant otpExpiresAt;
    private int otpAttempts;
    private String clientRef;
    private String providerRef;
    private String error;
    private Instant createdAt;
    private Instant updatedAt;
}
