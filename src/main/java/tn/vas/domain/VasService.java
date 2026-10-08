package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "vas_service")
@Getter
@Setter
public class VasService {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    @Enumerated(EnumType.STRING) private ServiceType type;
    @ManyToOne private Partner partner;
    @ManyToOne(optional=false) private ShortCode shortCode;
    @Enumerated(EnumType.STRING) private ServiceStatus status = ServiceStatus.DRAFT;
    private boolean regulated;
    private boolean regulatoryApproved;
    @Enumerated(EnumType.STRING) private ConsentMode consentMode = ConsentMode.SIMPLE_OPT_IN;
    private Instant opensAt;
    private Instant closesAt;
    private int maxActionsPerMsisdn;
    private String replyOk;
    private String replyStop;
    private String replyHelp;
    private String replyLimit;
    private String replyClosed;
}
