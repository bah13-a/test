package tn.vas.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chiffrement des numéros de téléphone au repos, DÉTERMINISTE (même numéro → même valeur) pour conserver les recherches par égalité,
 * l'unicité et les jointures. Construction « IV synthétique » : IV = HMAC-SHA256(clé_mac, numéro)[0..16] puis AES-256-CTR(clé_enc, IV) ;
 * à la lecture l'IV est recalculé et comparé (authenticité). Format : "enc:v1:" + base64url(IV || chiffré). Clés dérivées de vas.data-key.
 * Sans clé configurée (tests historiques), les valeurs restent en clair ; les valeurs en clair déjà en base restent lisibles.
 * Limite assumée : le déterminisme révèle les égalités (même numéro) mais pas le numéro ; la perte de la clé rend les numéros irrécupérables.
 */
@Component
public class MsisdnCrypto {
    static final String PREFIX = "enc:v1:";
    private static volatile SecretKeySpec encKey, macKey;

    public MsisdnCrypto(@Value("${vas.data-key:}") String dataKey) {
        configure(dataKey);
    }

    /** (Re)configure la clé de données ; chaîne vide = pas de chiffrement. */
    public static void configure(String dataKey) {
        if (dataKey == null || dataKey.isBlank()) { encKey = null; macKey = null; return; }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            encKey = new SecretKeySpec(sha.digest(("enc|" + dataKey).getBytes(StandardCharsets.UTF_8)), "AES");
            macKey = new SecretKeySpec(sha.digest(("mac|" + dataKey).getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean enabled() {
        return encKey != null;
    }

    public static String encrypt(String plain) {
        if (plain == null || encKey == null || plain.startsWith(PREFIX)) return plain;
        try {
            byte[] iv = Arrays.copyOf(hmac(plain), 16);
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, encKey, new IvParameterSpec(iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[16 + ct.length];
            System.arraycopy(iv, 0, all, 0, 16);
            System.arraycopy(ct, 0, all, 16, ct.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
        } catch (Exception e) {
            throw new IllegalStateException("chiffrement du numéro impossible", e);
        }
    }

    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        if (encKey == null) throw new IllegalStateException("valeur chiffrée en base mais vas.data-key absente");
        try {
            byte[] all = Base64.getUrlDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOf(all, 16);
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, encKey, new IvParameterSpec(iv));
            String plain = new String(c.doFinal(all, 16, all.length - 16), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(Arrays.copyOf(hmac(plain), 16), iv)) throw new IllegalStateException("numéro chiffré altéré ou mauvaise clé");
            return plain;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("déchiffrement du numéro impossible (mauvaise clé ?)", e);
        }
    }

    private static byte[] hmac(String plain) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(macKey);
        return m.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** Génère une clé de données aléatoire (outil d'installation). */
    public static String newKey() {
        byte[] b = new byte[48];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
