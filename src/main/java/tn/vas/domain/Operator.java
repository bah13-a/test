package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "operator")
@Getter
@Setter
public class Operator {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique=true) private String code;
    private String name;
    private String msisdnPrefixes;
    private String jasminConnector;
    @Enumerated(EnumType.STRING) private DlrBillingRule dlrBillingRule = DlrBillingRule.ON_DELIVERED;
    private int maxTps = 50;
    private String status = "ACTIVE";
    private boolean configApplied;
}
