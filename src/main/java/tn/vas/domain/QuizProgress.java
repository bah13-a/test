package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "quiz_progress")
@Getter
@Setter
public class QuizProgress {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private VasService service;
    @Convert(converter = tn.vas.security.MsisdnConverter.class) private String msisdn;
    private int currentPosition;
    private int score;
    private String status;
    private Instant startedAt;
    private Instant completedAt;
}
