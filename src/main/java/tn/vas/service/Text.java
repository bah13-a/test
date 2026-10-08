package tn.vas.service;

import java.util.Set;

/** Normalisation MSISDN et calcul d'encodage / segments SMS (exigences SMPP 5.2). */
public final class Text {
    private Text() {}

    private static final String GSM7_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final Set<Character> GSM7_EXT = Set.of('^', '{', '}', '\\', '[', '~', ']', '|', '€', '\f');

    public enum Encoding { GSM7, UCS2 }

    public record Sizing(Encoding encoding, int segments) {}

    /** Normalise en format international +216XXXXXXXX ; renvoie null si invalide. */
    public static String normalizeMsisdn(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[\\s\\-().]", "");
        if (d.startsWith("00")) d = "+" + d.substring(2);
        if (d.matches("\\d{8}")) d = "+216" + d;
        if (d.matches("216\\d{8}")) d = "+" + d;
        return d.matches("\\+216\\d{8}") ? d : null;
    }

    public static Sizing size(String text) {
        boolean gsm = true;
        int units = 0;
        for (char c : text.toCharArray()) {
            if (GSM7_BASIC.indexOf(c) >= 0) units++;
            else if (GSM7_EXT.contains(c)) units += 2;
            else { gsm = false; break; }
        }
        if (gsm) return new Sizing(Encoding.GSM7, units <= 160 ? 1 : (int) Math.ceil(units / 153.0));
        int len = text.length(); // unités UTF-16 = unités UCS-2 (paires de substitution comptées 2)
        return new Sizing(Encoding.UCS2, len <= 70 ? 1 : (int) Math.ceil(len / 67.0));
    }
}
