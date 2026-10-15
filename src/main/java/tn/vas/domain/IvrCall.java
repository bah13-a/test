package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ivr_call")
@Getter
@Setter
public class IvrCall {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String callId;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    @ManyToOne(optional = false) private Operator operator;
    @ManyToOne(optional = false) private MenuDefinition menu;
    private String path;
    private String status;
    private Instant startedAt;
    private Instant endedAt;
    private int durationSeconds;
}
