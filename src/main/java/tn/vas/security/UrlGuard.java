package tn.vas.security;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Protection SSRF des URL de webhook partenaires : https obligatoire, aucune adresse privée / loopback / link-local / metadata,
 * liste blanche d'hôtes optionnelle. Contrôle à l'enregistrement ET à chaque envoi (résolution DNS à ce moment-là).
 * Le profil dev autorise http et les adresses privées (vas.webhook.allow-http / allow-private).
 */
@Component
public class UrlGuard {
    private final boolean allowHttp;
    private final boolean allowPrivate;
    private final List<String> allowedHosts;

    public UrlGuard(@Value("${vas.webhook.allow-http:false}") boolean allowHttp,
                    @Value("${vas.webhook.allow-private:false}") boolean allowPrivate,
                    @Value("${vas.webhook.allowed-hosts:}") String allowedHosts) {
        this.allowHttp = allowHttp;
        this.allowPrivate = allowPrivate;
        this.allowedHosts = Arrays.stream(allowedHosts.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(String::toLowerCase).toList();
    }

    /** @throws IllegalArgumentException si l'URL est refusée */
    public void check(String url) {
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("URL webhook invalide");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!(scheme.equals("https") || (allowHttp && scheme.equals("http")))) throw new IllegalArgumentException("URL webhook : https obligatoire");
        if (u.getUserInfo() != null) throw new IllegalArgumentException("URL webhook : identifiants dans l'URL interdits");
        String host = u.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("URL webhook : hôte manquant");
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase()))
            throw new IllegalArgumentException("URL webhook : hôte non autorisé (" + host + ")");
        if (allowPrivate) return;
        try {
            resolvePublic(host);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * Résout un nom en n'acceptant QUE des adresses publiques. Utilisée à la connexion (voir {@link #dnsResolver()}) : la résolution qui
     * est contrôlée est celle qui sert à ouvrir la socket, ce qui supprime la fenêtre de « DNS rebinding » entre contrôle et connexion.
     */
    public InetAddress[] resolvePublic(String host) throws java.net.UnknownHostException {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (java.net.UnknownHostException e) {
            throw new java.net.UnknownHostException("URL webhook : hôte introuvable (" + host + ")");
        }
        if (allowPrivate) return addrs;
        for (InetAddress a : addrs) if (isPrivate(a)) throw new java.net.UnknownHostException("URL webhook : adresse non publique refusée (" + host + ")");
        return addrs;
    }

    /** Résolveur DNS pour le client HTTP des webhooks : toute connexion vers une adresse non publique échoue. */
    public org.apache.hc.client5.http.DnsResolver dnsResolver() {
        return new org.apache.hc.client5.http.DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws java.net.UnknownHostException { return resolvePublic(host); }
            @Override public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException { return host; }
        };
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            return b0 == 0 || (b0 == 100 && b1 >= 64 && b1 <= 127) /* CGNAT */ || (b0 == 198 && (b1 == 18 || b1 == 19)) || b0 >= 240;
        }
        return (b[0] & 0xfe) == 0xfc; // fc00::/7 (ULA)
    }
}
