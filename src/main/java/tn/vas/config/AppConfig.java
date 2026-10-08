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

    @Bean
    RestClient restClient() {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        return RestClient.builder().requestFactory(f).build();
    }
}
