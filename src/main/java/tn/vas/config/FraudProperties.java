package tn.vas.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Seuils de l'antifraude (variables FRAUD_* du .env). Un numéro qui dépasse un seuil de vitesse est bloqué blockMinutes minutes ;
 * le plafond de dépense journalier s'applique aux actions facturées (SMS surtaxés, USSD, IVR, DCB). STOP et AIDE ne sont jamais bloqués.
 */
@ConfigurationProperties(prefix = "vas.fraud")
public record FraudProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("10") int moPerMinute,
                              @DefaultValue("120") int moPerHour,
                              @DefaultValue("30") BigDecimal dailySpendLimit,
                              @DefaultValue("60") int blockMinutes,
                              @DefaultValue("5") double spikeFactor,
                              @DefaultValue("100") int spikeMinVolume,
                              @DefaultValue("4") double spikeZ,
                              @DefaultValue("60") int scoreAlert,
                              @DefaultValue("85") int scoreBlock) {}
