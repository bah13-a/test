package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "subscription")
@Getter
@Setter
public class Subscription {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    @ManyToOne(optional=false) private VasService service;
    @Enumerated(EnumType.STRING) private SubStatus status;
    private Instant activatedAt;
    private Instant nextRenewalAt;
    private Instant stoppedAt;
    private int renewalFailures;
}
