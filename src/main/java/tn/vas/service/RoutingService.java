package tn.vas.service;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.repo.Repos.*;

/**
 * Résolution de l'opérateur d'un numéro quand ni le service ni l'appelant ne l'indiquent. Ordre de priorité :
 * 1. numéro porté (exception exacte) ; 2. plus long préfixe de plage ; 3. préfixes déclarés sur l'opérateur (ancienne configuration).
 * Aucune plage n'est livrée par défaut : l'attribution des tranches et la portabilité sont des données officielles à charger depuis
 * l'opérateur ou le régulateur (import CSV). Les plages sont mises en cache 60 s (mises à jour visibles sur toutes les instances).
 */
@Service
public class RoutingService {
    private static final long TTL_MS = 60_000;
    private final NumberRangeRepo ranges;
    private final PortedNumberRepo ported;
    private final OperatorRepo operators;
    private final Clock clock;
    private volatile List<NumberRange> cache = List.of();
    private volatile long loadedAt;

    public RoutingService(NumberRangeRepo ranges, PortedNumberRepo ported, OperatorRepo operators, Clock clock) {
        this.ranges = ranges; this.ported = ported; this.operators = operators; this.clock = clock;
    }

    /** @param msisdn numéro normalisé (+216XXXXXXXX) */
    @Transactional(readOnly = true)
    public Optional<Operator> resolve(String msisdn) {
        var p = ported.findByMsisdn(msisdn);
        if (p.isPresent()) return Optional.of(p.get().getOperator());
        String national = msisdn.startsWith("+216") ? msisdn.substring(4) : msisdn;
        for (var r : sortedRanges()) if (national.startsWith(r.getPrefix())) return Optional.of(r.getOperator());
        Operator best = null;
        int len = -1;
        for (var o : operators.findAll()) {
            if (o.getMsisdnPrefixes() == null) continue;
            for (String pre : o.getMsisdnPrefixes().split(",")) {
                pre = pre.trim();
                if (!pre.isEmpty() && national.startsWith(pre) && pre.length() > len) { best = o; len = pre.length(); }
            }
        }
        return Optional.ofNullable(best);
    }

    private List<NumberRange> sortedRanges() {
        if (clock.millis() - loadedAt > TTL_MS) invalidate();
        return cache;
    }

    public synchronized void invalidate() {
        var all = new ArrayList<>(ranges.findAll());
        all.sort(Comparator.comparingInt((NumberRange r) -> r.getPrefix().length()).reversed());
        cache = all;
        loadedAt = clock.millis();
    }

    public record ImportResult(int created, int updated, int rejected, List<String> errors) {}

    /** Import CSV « préfixe;opérateur » (plages) ou « msisdn;opérateur » (numéros portés). Lignes invalides rejetées avec leur numéro de ligne. */
    @Transactional
    public ImportResult importCsv(String kind, Iterable<String> lines, String source) {
        int created = 0, updated = 0, rejected = 0, n = 0;
        List<String> errors = new ArrayList<>();
        var now = clock.instant();
        for (String raw : lines) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || (n == 1 && line.toLowerCase().matches("^(prefix|préfixe|msisdn)\\b.*"))) continue;
            String[] f = line.split("[;,]");
            Operator op = f.length < 2 ? null : operators.findByCode(f[1].strip().toUpperCase(Locale.ROOT)).orElse(null);
            if (op == null) { rejected++; if (errors.size() < 20) errors.add("ligne " + n + " : opérateur inconnu ou colonne manquante"); continue; }
            if ("ranges".equals(kind)) {
                String prefix = f[0].strip();
                if (!prefix.matches("\\d{1,8}")) { rejected++; if (errors.size() < 20) errors.add("ligne " + n + " : préfixe invalide « " + prefix + " »"); continue; }
                var r = ranges.findByPrefix(prefix).orElse(null);
                if (r == null) { r = new NumberRange(); r.setPrefix(prefix); created++; } else updated++;
                r.setOperator(op); r.setSource(source); r.setUpdatedAt(now);
                ranges.save(r);
            } else {
                String m = Text.normalizeMsisdn(f[0].strip());
                if (m == null) { rejected++; if (errors.size() < 20) errors.add("ligne " + n + " : numéro invalide « " + f[0].strip() + " »"); continue; }
                var r = ported.findByMsisdn(m).orElse(null);
                if (r == null) { r = new PortedNumber(); r.setMsisdn(m); created++; } else updated++;
                r.setOperator(op); r.setSource(source); r.setUpdatedAt(now);
                ported.save(r);
            }
        }
        invalidate();
        return new ImportResult(created, updated, rejected, errors);
    }
}
