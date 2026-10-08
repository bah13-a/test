package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "mt_status_history")
@Getter
@Setter
public class MtStatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long mtId;
    @Enumerated(EnumType.STRING) private MtStatus status;
    private String rawStatus;
    private Instant at;
}
