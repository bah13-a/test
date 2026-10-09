package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Règle d'exclusion (BLACK) ou d'autorisation exclusive (WHITE) ; service_id null = globale. */
@Entity
@Table(name = "msisdn_rule")
@Getter
@Setter
public class MsisdnRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String msisdn;
    @ManyToOne private VasService service;
    private String ruleType;
    private String reason;
    private Instant createdAt;
}
