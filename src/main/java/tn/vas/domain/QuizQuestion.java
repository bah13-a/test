package tn.vas.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "quiz_question")
@Getter
@Setter
public class QuizQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private VasService service;
    private int position;
    private String question;
    private String answers;
    private int points = 1;
    private String replyCorrect;
    private String replyWrong;
}
