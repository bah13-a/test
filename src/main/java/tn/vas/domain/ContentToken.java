package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "content_token")
@Getter
@Setter
public class ContentToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String token;
    @ManyToOne(optional=false) private ContentItem item;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    private Instant createdAt;
    private Instant expiresAt;
    private int uses;
}
