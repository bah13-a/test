package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "tariff")
@Getter
@Setter
public class Tariff {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private VasService service;
    @Enumerated(EnumType.STRING) private EventType eventType;
    private BigDecimal grossAmount;
    private BigDecimal operatorPercent;
    private BigDecimal taxPercent = BigDecimal.ZERO;
    private Instant effectiveFrom;
    private boolean approved;
    private String createdBy;
}
