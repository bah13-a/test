package tn.vas.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import tn.vas.config.VasProperties;
import tn.vas.repo.Repos.ApiClientRepo;

/**
 * Trois surfaces : /api/v1 (clé API + scopes), /callbacks (secret Jasmin), /admin + actuator (Basic + rôles RBAC).
 * Rôles : SUPER_ADMIN, NOC, VAS_MANAGER, FINANCE, SUPPORT, PARTNER, AUDITOR.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // hash attendu : {bcrypt}$2a$...
    }

    @Bean
    UserDetailsService users(VasProperties props) {
        var list = props.adminUsers() == null ? java.util.List.<VasProperties.AdminUser>of() : props.adminUsers();
        return new InMemoryUserDetailsManager(list.stream()
                .map(u -> User.withUsername(u.username()).password(u.passwordHash()).roles(u.roles().toArray(String[]::new)).build())
                .toList());
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, ApiClientRepo clients, RateLimiter limiter, VasProperties props) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> {})
            .addFilterBefore(new ApiKeyFilter(clients, limiter), BasicAuthenticationFilter.class)
            .addFilterBefore(new CallbackSecretFilter(props.callback().sharedSecret()), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/health", "/actuator/health/**", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/", "/index.html", "/app.js").permitAll()
                .requestMatchers("/callbacks/**").hasRole("GATEWAY")
                .requestMatchers("/api/v1/**").authenticated()
                .requestMatchers("/actuator/**").hasAnyRole("SUPER_ADMIN", "NOC")
                .requestMatchers("/admin/**").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }
}
