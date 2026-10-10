package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Plage de numéros nationaux (préfixe sans +216) attribuée à un opérateur. */
@Entity
@Table(name = "number_range")
@Getter
@Setter
public class NumberRange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String prefix;
    @ManyToOne(optional = false) private Operator operator;
    private String source;
    private Instant updatedAt;
}
