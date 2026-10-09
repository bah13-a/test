package tn.vas.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "service_reply")
@Getter
@Setter
public class ServiceReply {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private VasService service;
    private String lang;
    private String kind;
    private String text;
}
