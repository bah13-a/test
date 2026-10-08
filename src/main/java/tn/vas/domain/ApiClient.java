package tn.vas.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import static tn.vas.domain.Enums.*;

@Entity
@Table(name = "api_client")
@Getter
@Setter
public class ApiClient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    private String keyHash;
    private String scopes;
    @ManyToOne private Partner partner;
    private int rateLimitPerMin = 600;
    private boolean active = true;
}
