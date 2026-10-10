package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "vote_ballot")
@Getter
@Setter
public class VoteBallot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private VasService service;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    private String optionCode;
    private Instant createdAt;
    private Long moId;
}
