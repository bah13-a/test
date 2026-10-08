package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "mt_message")
@Getter
@Setter
public class MtMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String correlationId;
    private String clientRef;
    @ManyToOne(optional=false) private Operator operator;
    @ManyToOne private VasService service;
    private String msisdn;
    private String sender;
    private String content;
    private String encoding;
    private int segments;
    @Enumerated(EnumType.STRING) private Priority priority = Priority.TRANSACTIONAL;
    @Enumerated(EnumType.STRING) private MtStatus status = MtStatus.PENDING;
    private String rawStatus;
    private String smscMessageId;
    private int attempts;
    private boolean billable;
    private Long moId;
    private Long apiClientId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant validityUntil;
}
