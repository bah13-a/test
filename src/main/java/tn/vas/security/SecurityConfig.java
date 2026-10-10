package tn.vas.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    SecurityFilterChain chain(HttpSecurity http, ApiClientRepo clients, RateLimiter limiter, VasProperties props,
                           java.time.Clock clock, tn.vas.service.AuditService audit, TokenService tokens, UserService userService, AuthThrottle throttle) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.authenticationEntryPoint((req, res, ex) -> res.sendError(401, "unauthorized"))) // sans WWW-Authenticate : pas de boîte de dialogue navigateur
            .addFilterBefore(new BearerFilter(tokens, userService), BasicAuthenticationFilter.class)
            .addFilterBefore(new ApiKeyFilter(clients, limiter, tokens, throttle), BasicAuthenticationFilter.class)
            .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) -> {
                audit.log("ACCESS_DENIED", req.getMethod() + " " + req.getRequestURI(), null); // tentative d'accès refusée tracée (Annexe A14)
                res.sendError(403, "forbidden");
            }))
            .addFilterAfter(new MfaFilter(props.mfaEnforced(), clock), BasicAuthenticationFilter.class)
            .addFilterBefore(new CallbackSecretFilter(props.callback().sharedSecret()), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/auth/login", "/auth/env", "/oauth/token", "/error", "/dev/**", "/c/**", "/api/v1/health", "/actuator/health/**", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/", "/index.html", "/assets/**", "/favicon.svg").permitAll()
                .requestMatchers("/callbacks/**").hasRole("GATEWAY")
                .requestMatchers("/api/v1/**").authenticated()
                .requestMatchers("/actuator/**").hasAnyRole("SUPER_ADMIN", "NOC")
                .requestMatchers("/portal/**").hasRole("PARTNER")
                .requestMatchers("/admin/**").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }
}
