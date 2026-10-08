package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "recon_item")
@Getter
@Setter
public class ReconItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String batchId;
    @ManyToOne(optional=false) private Operator operator;
    private String eventId;
    private BigDecimal operatorAmount;
    private BigDecimal platformAmount;
    @Enumerated(EnumType.STRING) private ReconResult result;
    private String comment;
}
