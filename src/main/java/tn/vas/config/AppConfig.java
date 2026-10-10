package tn.vas.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Client HTTP des webhooks partenaires : la résolution DNS est contrôlée au moment de la connexion (adresses publiques uniquement),
     * pas de redirection, pas de nouvelle tentative automatique, délais courts.
     */
    @Bean("webhookHttp")
    public RestClient webhookHttp(tn.vas.security.UrlGuard guard) {
        var conn = org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(guard.dnsResolver())
                .setDefaultConnectionConfig(org.apache.hc.client5.http.config.ConnectionConfig.custom()
                        .setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(3)).setSocketTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(10)).build())
                .build();
        var client = org.apache.hc.client5.http.impl.classic.HttpClients.custom().setConnectionManager(conn).disableRedirectHandling().disableAutomaticRetries().build();
        return RestClient.builder().requestFactory(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(client)).build();
    }

    @Bean
    @org.springframework.context.annotation.Primary
    RestClient restClient() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        return RestClient.builder().requestFactory(f).build();
    }
}
