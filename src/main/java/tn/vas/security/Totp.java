package tn.vas.security;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** TOTP RFC 6238 (HMAC-SHA1, 6 chiffres, pas de 30 s, tolérance ±1 pas) compatible Google/Microsoft Authenticator. */
public final class Totp {
    private Totp() {}

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static String newSecret() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        return base32(b);
    }

    public static boolean verify(String secret, String code, long nowMillis) {
        if (secret == null || code == null || !code.matches("\\d{6}")) return false;
        long step = nowMillis / 30000;
        for (long i = -1; i <= 1; i++) {
            if (constantEq(generate(secret, step + i), code)) return true;
        }
        return false;
    }

    public static String generate(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String otpauthUri(String issuer, String user, String secret) {
        return "otpauth://totp/" + issuer + ":" + user + "?secret=" + secret + "&issuer=" + issuer + "&digits=6&period=30";
    }

    private static boolean constantEq(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte x : data) {
            buf = (buf << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(B32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] decode(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char c : s.toUpperCase().replace("=", "").toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) continue;
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) { out.write((buf >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }
}
