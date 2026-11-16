package ai.voicecraft.security;

import ai.voicecraft.common.ApiException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Identyfikator zalogowanego użytkownika (subject tokenu sesji). */
public final class CurrentUser {
    private CurrentUser() {}

    public static String id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Zaloguj się, aby kontynuować.");
        }
        return auth.getName();
    }

    /** Identyfikator zalogowanego użytkownika albo null (np. zadania systemowe). */
    public static String idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        return auth.getName();
    }

    /** Wykonuje kod w kontekście wskazanego użytkownika (np. w wątku kolejki generowania). */
    public static <T> T runAs(String userId, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId, null, java.util.List.of()));
        SecurityContextHolder.setContext(ctx);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
