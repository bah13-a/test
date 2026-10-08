package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "webhook_outbox")
@Getter
@Setter
public class WebhookOutbox {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String eventId;
    private String url;
    private String secret;
    private String payload;
    private String status; // PENDING | SENT | DEAD (DLQ)
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
}
