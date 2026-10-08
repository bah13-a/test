package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "consent_record")
@Getter
@Setter
public class ConsentRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String msisdn;
    @ManyToOne(optional=false) private VasService service;
    private String action;
    private String channel;
    private String proofText;
    private String termsVersion;
    private Instant at;
}
