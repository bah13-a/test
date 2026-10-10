package tn.vas.service;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.MoOutcome;
import tn.vas.repo.Repos.*;

/**
 * Quiz à questions successives : un MO mot-clé démarre ou reprend la partie, chaque MO suivant du même numéro est la réponse à la
 * question courante. Réponses acceptées : liste « a|b|c » normalisée (accents, casse, diacritiques arabes ignorés). Une partie par numéro.
 */
@Service
public class QuizService {
    private final QuizQuestionRepo questions;
    private final QuizProgressRepo progress;
    private final Clock clock;

    public QuizService(QuizQuestionRepo questions, QuizProgressRepo progress, Clock clock) {
        this.questions = questions;
        this.progress = progress;
        this.clock = clock;
    }

    public boolean configured(VasService svc) {
        return questions.countByService(svc) > 0;
    }

    /** Partie en cours de ce numéro sur ce short code (pour interpréter un MO « sans mot-clé » comme une réponse). */
    public QuizProgress active(String msisdn, ShortCode sc) {
        List<QuizProgress> l = progress.activeFor(msisdn, sc);
        return l.isEmpty() ? null : l.get(0);
    }

    @Transactional
    public Engines.Reply start(VasService svc, String msisdn, String lang) {
        var all = questions.findByServiceOrderByPositionAsc(svc);
        if (all.isEmpty()) return null;
        var p = progress.findByServiceAndMsisdn(svc, msisdn).orElse(null);
        if (p != null && "COMPLETED".equals(p.getStatus())) return Engines.Reply.free(Messages.text(lang, Messages.QUIZ_PLAYED), MoOutcome.LIMIT_REACHED);
        if (p == null) {
            p = new QuizProgress();
            p.setService(svc);
            p.setMsisdn(msisdn);
            p.setCurrentPosition(all.get(0).getPosition());
            p.setStatus("IN_PROGRESS");
            p.setStartedAt(clock.instant());
            progress.save(p);
        }
        return Engines.Reply.paid(questionText(all, p.getCurrentPosition(), lang));
    }

    @Transactional
    public Engines.Reply answer(QuizProgress p, String text, String lang) {
        var svc = p.getService();
        var all = questions.findByServiceOrderByPositionAsc(svc);
        var q = all.stream().filter(x -> x.getPosition() == p.getCurrentPosition()).findFirst().orElse(null);
        if (q == null) return null;
        String given = Engines.norm(text);
        boolean ok = Arrays.stream(q.getAnswers().split("\\|")).map(Engines::norm).anyMatch(a -> !a.isEmpty() && a.equals(given));
        if (ok) p.setScore(p.getScore() + q.getPoints());
        String feedback = ok ? orDefault(q.getReplyCorrect(), Messages.text(lang, Messages.QUIZ_CORRECT)) : orDefault(q.getReplyWrong(), Messages.text(lang, Messages.QUIZ_WRONG));
        var next = all.stream().filter(x -> x.getPosition() > q.getPosition()).findFirst().orElse(null);
        String tail;
        if (next == null) {
            p.setStatus("COMPLETED");
            p.setCompletedAt(clock.instant());
            tail = String.format(Messages.text(lang, Messages.QUIZ_DONE), p.getScore(), all.stream().mapToInt(QuizQuestion::getPoints).sum());
        } else {
            p.setCurrentPosition(next.getPosition());
            tail = questionText(all, next.getPosition(), lang);
        }
        progress.save(p);
        return Engines.Reply.paid(feedback + " " + tail);
    }

    private String questionText(List<QuizQuestion> all, int position, String lang) {
        int idx = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).getPosition() == position) idx = i;
        return String.format(Messages.text(lang, Messages.QUIZ_QUESTION), idx + 1, all.size(), all.get(idx).getQuestion());
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }
}
