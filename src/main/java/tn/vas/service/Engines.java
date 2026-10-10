package tn.vas.service;

import java.text.Normalizer;
import java.util.Locale;
import tn.vas.domain.Enums.EventType;
import tn.vas.domain.Enums.MoOutcome;

/** Résultat d'un moteur de service (vote, quiz, contenu) : texte du MT de réponse, type de facturation (null = gratuit) et issue du MO. */
public final class Engines {
    private Engines() {}

    public record Reply(String text, EventType billing, MoOutcome outcome) {
        public static Reply paid(String text) { return new Reply(text, EventType.MT, MoOutcome.ROUTED); }
        public static Reply free(String text, MoOutcome outcome) { return new Reply(text, null, outcome); }
    }

    /** Normalisation des réponses : minuscules, sans accents ni diacritiques arabes, variantes de alef unifiées, espaces compactés. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه');
        return n.replaceAll("[\\s\\p{Punct}]+", " ").trim();
    }
}
