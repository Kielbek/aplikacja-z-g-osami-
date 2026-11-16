package ai.voicecraft.publicapi;

import ai.voicecraft.account.AccountService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Osobny łańcuch zabezpieczeń dla publicznego API (/v1/**): tylko klucze API, bez ciasteczek sesji,
 * bez CORS (klucz nie powinien trafiać do przeglądarki — wywołuj API z własnego serwera).
 */
@Configuration
public class ApiSecurityConfig {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain publicApi(HttpSecurity http, ApiKeyService keys, AccountService accounts, ApiRateLimiter limiter) throws Exception {
        http
            .securityMatcher("/v1/**")
            .csrf(c -> c.disable())
            .cors(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .requestCache(r -> r.disable())
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/v1/openapi.json").permitAll()
                    .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) ->
                    ApiErrors.write(res, req, HttpStatus.UNAUTHORIZED, "invalid_api_key", "Brak ważnego klucza API.")))
            .headers(h -> h.contentTypeOptions(c -> {}).frameOptions(f -> f.deny()).cacheControl(c -> {}))
            .addFilterBefore(new ApiKeyFilter(keys, accounts, limiter), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
