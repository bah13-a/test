package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Blocage temporaire d'un numéro par l'antifraude (levée automatique à until_at, ou manuelle). */
@Entity
@Table(name = "fraud_block")
@Getter
@Setter
public class FraudBlock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    private String rule;
    private String reason;
    private Instant createdAt;
    private Instant untilAt;
    private String liftedBy;
}
