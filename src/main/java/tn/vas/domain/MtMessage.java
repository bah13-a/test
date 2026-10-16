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
    @Enumerated(EnumType.STRING) private Channel channel = Channel.SMS;
    private String correlationId;
    private String clientRef;
    @ManyToOne(optional=false) private Operator operator;
    @ManyToOne private VasService service;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
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
    private Long subscriptionId;
    private Instant scheduledAt;
    /** Client SMPP : accusé de livraison demandé / déjà remis. */
    private boolean smppDlr;
    private boolean smppDlrSent;
    /** registered_delivery & 0x03 : 1 = succès et échec, 2 = échec seulement. */
    private short smppDlrMode;
    /** Contenu structuré hors SMS (JSON) : modèle WhatsApp {template, lang, params}. */
    private String payload;
}
