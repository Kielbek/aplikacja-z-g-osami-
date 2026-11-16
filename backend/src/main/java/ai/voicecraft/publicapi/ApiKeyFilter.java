package ai.voicecraft.publicapi;

import ai.voicecraft.account.AccountService;
import ai.voicecraft.account.SubscriptionPlan;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Uwierzytelnianie publicznego API kluczem ({@code Authorization: Bearer nrv_live_…} lub {@code X-API-Key}).
 * Ciasteczka sesji są tu ignorowane — dzięki temu /v1 nie jest podatne na CSRF.
 * Kolejno: identyfikator żądania → klucz → plan z dostępem do API → limit żądań na minutę.
 */
class ApiKeyFilter extends OncePerRequestFilter {
    static final String KEY_ID = "narrivy.apiKeyId";

    private final ApiKeyService keys;
    private final AccountService accounts;
    private final ApiRateLimiter limiter;

    ApiKeyFilter(ApiKeyService keys, AccountService accounts, ApiRateLimiter limiter) {
        this.keys = keys;
        this.accounts = accounts;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        req.setAttribute(ApiErrors.REQUEST_ID, requestId);
        res.setHeader("X-Request-Id", requestId);
        if (req.getRequestURI().equals("/v1/openapi.json")) {
            chain.doFilter(req, res);
            return;
        }
        String secret = extract(req);
        if (secret == null) {
            ApiErrors.write(res, req, HttpStatus.UNAUTHORIZED, "missing_api_key",
                    "Brak klucza API. Wyślij nagłówek Authorization: Bearer <klucz> lub X-API-Key: <klucz>.");
            return;
        }
        ApiKey key = keys.authenticate(secret);
        if (key == null) {
            ApiErrors.write(res, req, HttpStatus.UNAUTHORIZED, "invalid_api_key", "Klucz API jest nieprawidłowy lub został unieważniony.");
            return;
        }
        SubscriptionPlan plan = accounts.get(key.userId).planId;
        SubscriptionPlan.ApiLimits limits = plan.api();
        if (!limits.enabled()) {
            ApiErrors.write(res, req, HttpStatus.FORBIDDEN, "plan_required", "Dostęp do API jest dostępny od planu "
                    + SubscriptionPlan.lowest(p -> p.api().enabled()).displayName + ". Twój plan: " + plan.displayName + ".");
            return;
        }
        ApiRateLimiter.Decision d = limiter.hit(key.userId, limits.requestsPerMinute());
        res.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));
        res.setHeader("X-RateLimit-Reset", String.valueOf(d.resetSeconds()));
        if (!d.allowed()) {
            res.setHeader("Retry-After", String.valueOf(d.resetSeconds()));
            ApiErrors.write(res, req, HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded",
                    "Przekroczono limit " + d.limit() + " żądań na minutę. Spróbuj ponownie za " + d.resetSeconds() + " s.");
            return;
        }
        req.setAttribute(KEY_ID, key.id);
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(key.userId, null, List.of()));
        SecurityContextHolder.setContext(ctx);
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String extract(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String v = auth.substring(7).trim();
            if (!v.isEmpty()) return v;
        }
        String header = req.getHeader("X-API-Key");
        return header == null || header.isBlank() ? null : header.trim();
    }
}
