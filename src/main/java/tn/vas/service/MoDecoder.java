package tn.vas.service;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Décodage du contenu d'un MO reçu de Jasmin. Jasmin envoie le contenu brut (param. content) ET sa version hexadécimale (binary)
 * avec le data_coding SMPP (coding) : on décode toujours depuis binary + coding (le champ content est corrompu pour l'UCS-2 et les
 * accents car il passe par un décodage de formulaire). Codages gérés : 0 (alphabet par défaut GSM 03.38), 1 (ASCII), 3 (Latin-1), 8 (UCS-2).
 */
public final class MoDecoder {
    private MoDecoder() {}

    // GSM 03.38 table de base, indexée par code (0x1B = échappement vers la table d'extension)
    static final String BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    static char ext(int code) {
        return switch (code) {
            case 0x0A -> '\f'; case 0x14 -> '^'; case 0x28 -> '{'; case 0x29 -> '}'; case 0x2F -> '\\';
            case 0x3C -> '['; case 0x3D -> '~'; case 0x3E -> ']'; case 0x40 -> '|'; case 0x65 -> '€';
            default -> ' ';
        };
    }

    /**
     * Jasmin 0.11 envoie data_coding comme UN OCTET BRUT (ex. %00, %08), pas comme un nombre décimal (constaté en capturant la requête).
     * On accepte aussi une écriture décimale ("8") pour les versions/proxys qui la normalisent.
     */
    public static Integer parseCoding(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (raw.length() == 1 && !Character.isDigit(raw.charAt(0))) return (int) raw.charAt(0);
        if (raw.matches("\\d{1,3}")) return Integer.parseInt(raw);
        return (int) raw.charAt(0);
    }

    /** @param hex contenu hexadécimal (peut être null) ; @param fallback valeur du champ content si hex absent */
    public static String decode(String hex, Integer coding, String fallback) {
        if (hex == null || hex.isBlank()) return fallback == null ? "" : fallback;
        byte[] b = HexFormat.of().parseHex(hex.trim());
        int c = coding == null ? 0 : coding;
        return switch (c) {
            case 8 -> new String(b, StandardCharsets.UTF_16BE);
            case 1 -> new String(b, StandardCharsets.US_ASCII);
            case 3 -> new String(b, StandardCharsets.ISO_8859_1);
            case 0 -> gsm7(b);
            default -> new String(b, StandardCharsets.ISO_8859_1); // 2/4 : binaire, conservé tel quel (Latin-1)
        };
    }

    static String gsm7(byte[] b) {
        // un octet >= 0x80 n'existe pas dans l'alphabet GSM déprotégé : certains SMSC y envoient du Latin-1 avec data_coding 0
        for (byte x : b) if ((x & 0x80) != 0) return new String(b, StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0x7f;
            if (v == 0x1B && i + 1 < b.length) sb.append(ext(b[++i] & 0x7f));
            else sb.append(BASIC.charAt(v));
        }
        return sb.toString();
    }

    /** Encodage GSM 03.38 (octets non compactés, un septet par octet) ; null si un caractère n'appartient pas à l'alphabet. */
    public static byte[] encodeGsm7(String text) {
        var out = new java.io.ByteArrayOutputStream();
        for (char c : text.toCharArray()) {
            int i = BASIC.indexOf(c);
            if (i >= 0 && i != 0x1B) { out.write(i); continue; }
            int e = -1;
            for (int code : new int[]{0x0A, 0x14, 0x28, 0x29, 0x2F, 0x3C, 0x3D, 0x3E, 0x40, 0x65}) if (ext(code) == c) { e = code; break; }
            if (e < 0) return null;
            out.write(0x1B);
            out.write(e);
        }
        return out.toByteArray();
    }
}
