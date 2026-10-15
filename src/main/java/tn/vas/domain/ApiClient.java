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
    /** Accès SMPP (serveur SMPP v3.4 de la plateforme) : null = pas d'accès SMPP. */
    private String smppSystemId;
    private String smppPasswordHash;
    /** Adresses IP autorisées (séparées par des virgules) ; vide = toutes (déconseillé hors VPN). */
    private String smppAllowedIps;
    private int smppMaxBinds = 2;
    /** Le client reçoit en deliver_sm les MO de ses services. */
    private boolean smppMo;
}
