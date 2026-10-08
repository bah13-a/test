package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "mo_message")
@Getter
@Setter
public class MoMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private Operator operator;
    private String dedupKey;
    private String operatorMsgId;
    private String msisdn;
    private String shortCode;
    private String content;
    private Instant receivedAt;
    @ManyToOne private VasService service;
    @Enumerated(EnumType.STRING) private MoOutcome outcome;
}
