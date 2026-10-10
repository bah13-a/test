package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Numéro dont l'opérateur actuel diffère de celui de sa plage (portabilité). Numéro chiffré comme les autres. */
@Entity
@Table(name = "ported_number")
@Getter
@Setter
public class PortedNumber {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    @ManyToOne(optional = false) private Operator operator;
    private String source;
    private Instant updatedAt;
}
