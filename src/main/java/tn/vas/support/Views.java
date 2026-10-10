package tn.vas.support;

import java.util.Arrays;

/** Aides de présentation partagées par les côtés commande et requête. */
public final class Views {
    private Views() {}

    /** Masque un numéro : tous les chiffres sauf les cinq premiers caractères visibles avant les cinq derniers masqués. */
    public static String mask(String msisdn) {
        return msisdn == null || msisdn.length() < 6 ? msisdn : msisdn.substring(0, msisdn.length() - 5) + "*****";
    }

    public static boolean hasAnyRole(String... roles) {
        var a = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream().anyMatch(g -> Arrays.asList(roles).contains(g.getAuthority().substring(5)));
    }
}
