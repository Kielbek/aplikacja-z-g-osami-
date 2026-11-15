package ai.voicecraft.security;

import ai.voicecraft.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {

    /** Endpointy dostępne bez logowania. */
    static final String[] PUBLIC = {
        "/api/auth/login", "/api/auth/register", "/api/auth/google", "/api/auth/config", "/api/auth/logout",
        "/api/auth/password-reset", "/api/auth/password-reset/confirm", "/api/engine/status", "/api/billing/webhook",
    };
    private static final Set<String> PUBLIC_SET = Set.of(PUBLIC);

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, SessionTokens tokens, UserRepository users) throws Exception {
        AuthenticationEntryPoint unauthorized = (req, res, ex) -> {
            res.setStatus(401);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"status\":401,\"message\":\"Sesja wygasła lub nie jesteś zalogowany.\",\"code\":\"UNAUTHENTICATED\"}");
        };
        http
            // Ochrona CSRF: ciasteczko SameSite=Lax + wymagany nagłówek X-Requested-With (filtr niżej)
            .csrf(c -> c.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .authorizeHttpRequests(a -> a
                    .requestMatchers(PUBLIC).permitAll()
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll())
            .oauth2ResourceServer(o -> o
                    .bearerTokenResolver(resolver())
                    .authenticationEntryPoint(unauthorized)
                    .jwt(j -> j.decoder(tokens.decoder()).jwtAuthenticationConverter(converter(users))))
            .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized))
            .headers(h -> h
                    .contentTypeOptions(Customizer.withDefaults())
                    .frameOptions(f -> f.deny())
                    .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
            .addFilterBefore(new RequestedWithFilter(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /** Token z ciasteczka sesji (przeglądarka) lub nagłówka Authorization (klienci API). */
    private static BearerTokenResolver resolver() {
        var header = new DefaultBearerTokenResolver();
        return request -> {
            if (PUBLIC_SET.contains(request.getRequestURI())) return null; // nieważne ciasteczko nie blokuje logowania
            String fromHeader = header.resolve(request);
            if (fromHeader != null) return fromHeader;
            Cookie[] cookies = request.getCookies();
            if (cookies == null) return null;
            for (Cookie c : cookies) {
                if (SessionTokens.COOKIE.equals(c.getName()) && !c.getValue().isBlank()) return c.getValue();
            }
            return null;
        };
    }

    /** Sesja jest ważna tylko, gdy użytkownik istnieje, a wersja tokenu zgadza się z bazą. */
    private static Converter<Jwt, AbstractAuthenticationToken> converter(UserRepository users) {
        return jwt -> {
            var user = users.findById(jwt.getSubject())
                    .orElseThrow(() -> new InvalidBearerTokenException("Konto nie istnieje."));
            Object ver = jwt.getClaims().get("ver");
            if (ver == null || ((Number) ver).intValue() != user.tokenVersion) {
                throw new InvalidBearerTokenException("Sesja została unieważniona.");
            }
            return UsernamePasswordAuthenticationToken.authenticated(user.id, null, List.of());
        };
    }

    /**
     * Żądania zmieniające stan muszą mieć nagłówek X-Requested-With (nie da się go wysłać z formularza
     * innej witryny). Wyjątek: webhook Stripe (podpisywany kryptograficznie).
     */
    static final class RequestedWithFilter extends OncePerRequestFilter {
        private static final Set<String> SAFE = Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String uri = req.getRequestURI();
            if (uri.startsWith("/api/") && !SAFE.contains(req.getMethod()) && !uri.equals("/api/billing/webhook")
                    && req.getHeader("X-Requested-With") == null) {
                res.setStatus(403);
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.setCharacterEncoding("UTF-8");
                res.getWriter().write("{\"status\":403,\"message\":\"Brak nagłówka X-Requested-With.\"}");
                return;
            }
            chain.doFilter(req, res);
        }
    }
}
