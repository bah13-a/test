package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "content_item")
@Getter
@Setter
public class ContentItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private VasService service;
    private String code;
    private String title;
    private String body;
    private String url;
    private int maxUses = 1;
    private int ttlHours = 24;
}
