package tn.vas.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

/** Contenu premium : le MO (ou l'API) déclenche un lien à jeton à durée et nombre d'usages limités, envoyé par MT facturable. */
@Service
public class ContentService {
    public enum Access { OK, UNKNOWN, EXPIRED }

    public record Consumed(Access access, ContentItem item) {}

    private final ContentItemRepo items;
    private final ContentTokenRepo tokens;
    private final MtService mt;
    private final Clock clock;
    private final String publicUrl;
    private final SecureRandom random = new SecureRandom();

    public ContentService(ContentItemRepo items, ContentTokenRepo tokens, MtService mt, Clock clock,
                          @Value("${vas.public-content-url:${vas.public-base-url:http://localhost:8080}}") String publicUrl) {
        this.items = items;
        this.tokens = tokens;
        this.mt = mt;
        this.clock = clock;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    public boolean configured(VasService svc) {
        return !items.findByServiceOrderById(svc).isEmpty();
    }

    /** @return null si le service n'a aucun contenu déclaré */
    @Transactional
    public Engines.Reply handle(VasService svc, String msisdn, String[] words, String lang) {
        List<ContentItem> all = items.findByServiceOrderById(svc);
        if (all.isEmpty()) return null;
        ContentItem item = pick(all, words.length > 1 ? words[1] : null);
        if (item == null) {
            String list = all.stream().map(ContentItem::getCode).collect(Collectors.joining(", "));
            return Engines.Reply.free(String.format(Messages.text(lang, Messages.CONTENT_UNKNOWN), list), MoOutcome.INVALID_CHOICE);
        }
        return Engines.Reply.paid(linkText(item, issue(item, msisdn), lang));
    }

    /** Émission d'un lien par l'API partenaire : crée le jeton et envoie le MT facturable. */
    @Transactional
    public MtMessage sendLink(VasService svc, String msisdn, String code, Long apiClientId) {
        List<ContentItem> all = items.findByServiceOrderById(svc);
        ContentItem item = pick(all, code);
        if (item == null) throw new IllegalArgumentException("contenu inconnu");
        String text = linkText(item, issue(item, msisdn), svc.getDefaultLang());
        var sc = svc.getShortCode();
        return mt.submit(new MtService.Request(sc.getOperator(), svc, msisdn, sc.getNumber(), text, Priority.TRANSACTIONAL, null, EventType.MT, null, apiClientId, Duration.ofHours(24)));
    }

    private ContentItem pick(List<ContentItem> all, String code) {
        if (code == null || code.isBlank()) return all.size() == 1 ? all.get(0) : null;
        return all.stream().filter(i -> i.getCode().equalsIgnoreCase(code)).findFirst().orElse(null);
    }

    private ContentToken issue(ContentItem item, String msisdn) {
        byte[] raw = new byte[18];
        random.nextBytes(raw);
        var t = new ContentToken();
        t.setToken(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
        t.setItem(item);
        t.setMsisdn(msisdn);
        t.setCreatedAt(clock.instant());
        t.setExpiresAt(clock.instant().plus(Duration.ofHours(item.getTtlHours())));
        return tokens.save(t);
    }

    private String linkText(ContentItem item, ContentToken t, String lang) {
        return String.format(Messages.text(lang, Messages.CONTENT_LINK), publicUrl + "/c/" + t.getToken(), item.getTtlHours(), item.getMaxUses());
    }

    /** Consomme une utilisation du jeton (accès au contenu) : refusé s'il est inconnu, expiré ou épuisé. */
    @Transactional
    public Consumed consume(String token) {
        Optional<ContentToken> t = tokens.findByToken(token == null ? "" : token);
        if (t.isEmpty()) return new Consumed(Access.UNKNOWN, null);
        var tk = t.get();
        if (clock.instant().isAfter(tk.getExpiresAt()) || tk.getUses() >= tk.getItem().getMaxUses()) return new Consumed(Access.EXPIRED, null);
        tk.setUses(tk.getUses() + 1);
        tokens.save(tk);
        return new Consumed(Access.OK, tk.getItem());
    }

    public static String code(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
