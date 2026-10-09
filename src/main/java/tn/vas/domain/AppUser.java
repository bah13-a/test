package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String username;
    private String passwordHash;
    private String roles; // CSV : SUPER_ADMIN,NOC,VAS_MANAGER,FINANCE,SUPPORT,AUDITOR,PARTNER
    @ManyToOne private Partner partner;
    private String totpSecret;
    private boolean mfaEnabled;
    private boolean active = true;
    private int failedLogins;
    private Instant lockedUntil;
}
