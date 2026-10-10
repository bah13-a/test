package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "partner")
@Getter
@Setter
public class Partner {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    private BigDecimal sharePercent = BigDecimal.ZERO;
    private String webhookUrl;
    private String webhookSecret;
    private int maxTps;
}
