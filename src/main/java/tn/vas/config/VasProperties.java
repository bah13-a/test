package tn.vas.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vas")
public record VasProperties(Jasmin jasmin, Callback callback, Retry retry, List<AdminUser> adminUsers,
                            String queue, String rateLimit, String publicBaseUrl, int moDedupSeconds) {
    /** Jasmin HTTP API. Un utilisateur Jasmin par opérateur ("vas_&lt;code&gt;") porte la route MT vers le bon connecteur SMPP. */
    public record Jasmin(String baseUrl, String password, boolean simulator) {}
    public record Callback(String sharedSecret) {}
    public record Retry(int maxAttempts, long backoffSeconds) {}
    public record AdminUser(String username, String passwordHash, List<String> roles) {}
}
