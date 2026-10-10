package tn.vas.web;

import tn.vas.support.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.domain.*;
import tn.vas.domain.Enums.ServiceType;
import tn.vas.repo.Repos.*;
import tn.vas.service.AuditService;
import tn.vas.service.CampaignService;

/** Configuration des moteurs (options de vote, questions de quiz, contenus premium) et pilotage des campagnes. */
@RestController
@RequestMapping("/admin")
public class EngineAdminController {
    private final ServiceRepo services;
    private final VoteOptionRepo options;
    private final QuizQuestionRepo questions;
    private final QuizProgressRepo quiz;
    private final ContentItemRepo items;
    private final CampaignService campaigns;
    private final AuditService audit;

    public EngineAdminController(ServiceRepo services, VoteOptionRepo options, QuizQuestionRepo questions, QuizProgressRepo quiz, ContentItemRepo items,
                                 CampaignService campaigns, AuditService audit) {
        this.services = services; this.options = options; this.questions = questions; this.quiz = quiz; this.items = items; this.campaigns = campaigns; this.audit = audit;
    }

    private VasService svc(Long id, ServiceType expected) {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (expected != null && s.getType() != expected) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "service de type " + s.getType() + ", " + expected + " attendu");
        return s;
    }

    // ---- vote
    public record OptionReq(Long serviceId, String code, String label) {}

    @GetMapping("/vote/options")
    @PreAuthorize(AdminController.ANY)
    public List<Map<String, Object>> voteOptions(@RequestParam Long serviceId) {
        return options.findByServiceOrderById(svc(serviceId, null)).stream().map(o -> Map.<String, Object>of("id", o.getId(), "code", o.getCode(), "label", o.getLabel())).toList();
    }

    @PostMapping("/vote/options")
    @PreAuthorize(AdminController.MGR)
    public Map<String, Object> addOption(@RequestBody OptionReq r) {
        var s = svc(r.serviceId(), ServiceType.VOTE);
        if (s.getStatus() == Enums.ServiceStatus.CLOSED) throw new ResponseStatusException(HttpStatus.CONFLICT, "campagne clôturée");
        String code = r.code() == null ? "" : r.code().trim().toUpperCase(Locale.ROOT);
        if (!code.matches("\\S{1,40}") || r.label() == null || r.label().isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "code (sans espace) et libellé requis");
        if (options.findByServiceAndCodeIgnoreCase(s, code).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "option déjà déclarée");
        var o = new VoteOption();
        o.setService(s); o.setCode(code); o.setLabel(r.label().trim());
        audit.log("VOTE_OPTION_CREATE", "service:" + s.getId(), code);
        return Map.of("id", options.save(o).getId());
    }

    @DeleteMapping("/vote/options/{id}")
    @PreAuthorize(AdminController.MGR)
    public ResponseEntity<Void> deleteOption(@PathVariable Long id) {
        options.deleteById(id);
        audit.log("VOTE_OPTION_DELETE", "option:" + id, null);
        return ResponseEntity.noContent().build();
    }

    // ---- quiz
    public record QuestionReq(Long serviceId, Integer position, String question, String answers, Integer points, String replyCorrect, String replyWrong) {}

    @GetMapping("/quiz/questions")
    @PreAuthorize(AdminController.ANY)
    public List<Map<String, Object>> quizQuestions(@RequestParam Long serviceId) {
        return questions.findByServiceOrderByPositionAsc(svc(serviceId, null)).stream().map(q -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId()); m.put("position", q.getPosition()); m.put("question", q.getQuestion()); m.put("answers", q.getAnswers()); m.put("points", q.getPoints());
            return m;
        }).toList();
    }

    @PostMapping("/quiz/questions")
    @PreAuthorize(AdminController.MGR)
    public Map<String, Object> addQuestion(@RequestBody QuestionReq r) {
        var s = svc(r.serviceId(), ServiceType.QUIZ);
        if (r.question() == null || r.question().isBlank() || r.answers() == null || r.answers().isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "question et réponses requises");
        int pos = r.position() != null ? r.position() : questions.findByServiceOrderByPositionAsc(s).stream().mapToInt(QuizQuestion::getPosition).max().orElse(0) + 1;
        if (questions.findByServiceAndPosition(s, pos).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "position déjà utilisée");
        var q = new QuizQuestion();
        q.setService(s); q.setPosition(pos); q.setQuestion(r.question().trim()); q.setAnswers(r.answers().trim());
        q.setPoints(r.points() == null ? 1 : Math.max(1, r.points())); q.setReplyCorrect(r.replyCorrect()); q.setReplyWrong(r.replyWrong());
        audit.log("QUIZ_QUESTION_CREATE", "service:" + s.getId(), "position " + pos);
        return Map.of("id", questions.save(q).getId(), "position", pos);
    }

    @DeleteMapping("/quiz/questions/{id}")
    @PreAuthorize(AdminController.MGR)
    public ResponseEntity<Void> deleteQuestion(@PathVariable Long id) {
        questions.deleteById(id);
        audit.log("QUIZ_QUESTION_DELETE", "question:" + id, null);
        return ResponseEntity.noContent().build();
    }

    // ---- contenu premium
    public record ItemReq(Long serviceId, String code, String title, String body, String url, Integer maxUses, Integer ttlHours) {}

    @GetMapping("/content/items")
    @PreAuthorize(AdminController.ANY)
    public List<Map<String, Object>> contentItems(@RequestParam Long serviceId) {
        return items.findByServiceOrderById(svc(serviceId, null)).stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId()); m.put("code", i.getCode()); m.put("title", i.getTitle()); m.put("maxUses", i.getMaxUses()); m.put("ttlHours", i.getTtlHours());
            return m;
        }).toList();
    }

    @PostMapping("/content/items")
    @PreAuthorize(AdminController.MGR)
    public Map<String, Object> addItem(@RequestBody ItemReq r) {
        var s = svc(r.serviceId(), ServiceType.PREMIUM_CONTENT);
        String code = r.code() == null ? "" : r.code().trim().toUpperCase(Locale.ROOT);
        if (!code.matches("\\S{1,40}") || r.title() == null || r.title().isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "code et titre requis");
        if (r.url() != null && !r.url().isBlank() && !r.url().matches("https://\\S+")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "url https requise");
        if (items.findByServiceAndCode(s, code).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "code déjà utilisé");
        var i = new ContentItem();
        i.setService(s); i.setCode(code); i.setTitle(r.title().trim()); i.setBody(r.body()); i.setUrl(r.url());
        i.setMaxUses(r.maxUses() == null ? 1 : Math.max(1, r.maxUses())); i.setTtlHours(r.ttlHours() == null ? 24 : Math.max(1, r.ttlHours()));
        audit.log("CONTENT_ITEM_CREATE", "service:" + s.getId(), code);
        return Map.of("id", items.save(i).getId());
    }

    @DeleteMapping("/content/items/{id}")
    @PreAuthorize(AdminController.MGR)
    public ResponseEntity<Void> deleteItem(@PathVariable Long id) {
        try {
            items.deleteById(id);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "des liens ont déjà été émis pour ce contenu");
        }
        audit.log("CONTENT_ITEM_DELETE", "item:" + id, null);
        return ResponseEntity.noContent().build();
    }



    @PostMapping("/services/{id}/close")
    @PreAuthorize(AdminController.MGR)
    public Map<String, Object> close(@PathVariable Long id) {
        try {
            return campaigns.close(svc(id, null));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
