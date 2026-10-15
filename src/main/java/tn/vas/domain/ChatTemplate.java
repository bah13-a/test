package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Modèle WhatsApp déclaré pour un service : même nom et même langue que dans le gestionnaire WhatsApp de Meta, corps avec {{1}}..{{n}}.
 * Utilisable seulement après approbation (par Meta, constatée dans le back-office par un autre administrateur que son auteur).
 */
@Entity
@Table(name = "chat_template")
@Getter
@Setter
public class ChatTemplate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private VasService service;
    private String name;
    private String lang;
    private String category;
    private String body;
    private int paramCount;
    private boolean approved;
    private String approvedBy;
    private String createdBy;
    private Instant createdAt;
}
