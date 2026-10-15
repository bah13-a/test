package tn.vas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Serveur SMPP v3.4 pour clients externes (variables SMPP_SERVER_* du .env). Désactivé par défaut. */
@ConfigurationProperties(prefix = "vas.smpp-server")
public record SmppServerProperties(@DefaultValue("false") boolean enabled,
                                   @DefaultValue("2777") int port,
                                   @DefaultValue("10") int bindFailuresPerMinute,
                                   @DefaultValue("6") int maxParts,
                                   @DefaultValue("120") int concatTimeoutSeconds) {}
