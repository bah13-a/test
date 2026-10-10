package tn.vas.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.vas.security.RateLimiter;
import tn.vas.service.ContentService;

/** Accès public au contenu premium par lien à jeton (/c/{token}) : durée et nombre d'usages limités, quota par IP. */
@RestController
public class PublicContentController {
    private final ContentService content;
    private final RateLimiter limiter;

    public PublicContentController(ContentService content, RateLimiter limiter) {
        this.content = content;
        this.limiter = limiter;
    }

    @GetMapping("/c/{token}")
    public ResponseEntity<?> open(@PathVariable String token, HttpServletRequest req) {
        if (!limiter.allow("content:" + req.getRemoteAddr(), 120)) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").body(Map.of("error", "trop de requêtes"));
        var r = content.consume(token);
        boolean html = String.valueOf(req.getHeader("Accept")).contains("text/html");
        return switch (r.access()) {
            case UNKNOWN -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "lien inconnu"));
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "lien expiré ou déjà utilisé"));
            case OK -> html
                    ? ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).header("Cache-Control", "no-store").body(page(r.item()))
                    : ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("title", r.item().getTitle(), "body", r.item().getBody() == null ? "" : r.item().getBody(),
                            "url", r.item().getUrl() == null ? "" : r.item().getUrl()));
        };
    }

    private static String page(tn.vas.domain.ContentItem i) {
        String link = i.getUrl() == null || i.getUrl().isBlank() ? "" : "<p><a href=\"" + esc(i.getUrl()) + "\" rel=\"noopener noreferrer\">" + esc(i.getUrl()) + "</a></p>";
        return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" + esc(i.getTitle())
                + "</title></head><body><main><h1>" + esc(i.getTitle()) + "</h1><p>" + esc(i.getBody() == null ? "" : i.getBody()) + "</p>" + link + "</main></body></html>";
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
