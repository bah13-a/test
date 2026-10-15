package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Alerte antifraude : OPEN tant qu'un opérateur ne l'a pas acquittée (ACK). */
@Entity
@Table(name = "fraud_alert")
@Getter
@Setter
public class FraudAlert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String rule;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    @ManyToOne private VasService service;
    private String detail;
    private String status;
    private Instant createdAt;
    private String acknowledgedBy;
    private Instant acknowledgedAt;
}
