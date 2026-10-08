package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "short_code")
@Getter
@Setter
public class ShortCode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String number;
    @ManyToOne(optional=false) private Operator operator;
    private Instant validFrom;
    private Instant validTo;
    private String status = "ACTIVE";
}
