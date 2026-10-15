package tn.vas.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Compte WhatsApp (phone_number_id) ou agent RCS rattaché à un short code : ses mots-clés et services s'appliquent au canal. */
@Entity
@Table(name = "chat_channel")
@Getter
@Setter
public class ChatChannel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) private Enums.Channel kind;
    private String address;
    @ManyToOne(optional = false) private ShortCode shortCode;
    private boolean active = true;
}
