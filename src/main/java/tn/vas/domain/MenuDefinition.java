package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Menu interactif USSD (code, ex. *123#) ou IVR (numéro appelé) : arbre JSON, voir tn.vas.channel.MenuTree. */
@Entity
@Table(name = "menu_definition")
@Getter
@Setter
public class MenuDefinition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) private Enums.Channel channel;
    private String code;
    @ManyToOne(optional = false) private VasService service;
    private String definition;
    private boolean active = true;
    private String updatedBy;
    private Instant updatedAt;
}
