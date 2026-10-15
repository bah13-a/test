package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "fraud_score")
@Getter
@Setter
public class FraudScore {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    private int score;
    private String features;
    private String decision;
    private Instant computedAt;
}
