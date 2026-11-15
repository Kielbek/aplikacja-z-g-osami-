package ai.voicecraft.auth;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import ai.voicecraft.security.CurrentUser;
import ai.voicecraft.security.SessionTokens;
import ai.voicecraft.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final SessionTokens sessions;
    private final AppProperties props;
    private final MailService mail;
    private final RestClient google = RestClient.create();

    public AuthController(AuthService auth, SessionTokens sessions, AppProperties props, MailService mail) {
        this.auth = auth;
        this.sessions = sessions;
        this.props = props;
        this.mail = mail;
    }

    public record SessionUser(String id, String email, String name, String picture, boolean hasPassword, boolean google) {
        static SessionUser of(User u) {
            return new SessionUser(u.id, u.email, u.fullName, u.pictureUrl, u.passwordHash != null, u.googleSub != null);
        }
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        var out = new LinkedHashMap<String, Object>();
        out.put("googleEnabled", props.auth().googleEnabled());
        out.put("googleClientId", props.auth().googleEnabled() ? props.auth().googleClientId() : null);
        out.put("emailDelivery", mail.configured());
        if (props.seedDemoData()) out.put("demoAccount", Map.of("email", DemoAccount.EMAIL, "password", DemoAccount.PASSWORD));
        return out;
    }

    public record Register(String name, String email, String password, Boolean remember) {}

    @PostMapping("/register")
    public SessionUser register(@RequestBody Register req, HttpServletResponse res) {
        User u = auth.register(req.name(), req.email(), req.password());
        sessions.write(res, u, !Boolean.FALSE.equals(req.remember()));
        return SessionUser.of(u);
    }

    public record Login(String email, String password, Boolean remember) {}

    @PostMapping("/login")
    public SessionUser login(@RequestBody Login req, HttpServletRequest http, HttpServletResponse res) {
        User u = auth.login(req.email(), req.password(), http.getRemoteAddr());
        sessions.write(res, u, !Boolean.FALSE.equals(req.remember()));
        return SessionUser.of(u);
    }

    @PostMapping("/logout")
    public void logout(HttpServletResponse res) {
        sessions.clear(res);
    }

    @GetMapping("/me")
    public SessionUser me() {
        return SessionUser.of(auth.get(CurrentUser.id()));
    }

    /** Unieważnia wszystkie sesje (wszystkie urządzenia) i loguje ponownie bieżące urządzenie. */
    @PostMapping("/logout-all")
    public SessionUser logoutAll(HttpServletResponse res) {
        User u = auth.invalidateSessions(CurrentUser.id());
        sessions.write(res, u, true);
        return SessionUser.of(u);
    }

    public record ChangePassword(String currentPassword, String newPassword) {}

    @PostMapping("/password")
    public SessionUser changePassword(@RequestBody ChangePassword req, HttpServletResponse res) {
        User u = auth.changePassword(CurrentUser.id(), req.currentPassword(), req.newPassword());
        sessions.write(res, u, true);
        return SessionUser.of(u);
    }

    public record ResetRequest(String email) {}

    @PostMapping("/password-reset")
    public Map<String, Object> requestReset(@RequestBody ResetRequest req, HttpServletRequest http) {
        auth.requestPasswordReset(req.email(), http.getRemoteAddr());
        return Map.of("sent", true);
    }

    public record ResetConfirm(String token, String password) {}

    @PostMapping("/password-reset/confirm")
    public SessionUser confirmReset(@RequestBody ResetConfirm req, HttpServletResponse res) {
        User u = auth.confirmPasswordReset(req.token(), req.password());
        sessions.write(res, u, true);
        return SessionUser.of(u);
    }

    public record DeleteAccount(String password) {}

    @PostMapping("/delete-account")
    public void deleteAccount(@RequestBody DeleteAccount req, HttpServletResponse res) {
        auth.deleteAccount(CurrentUser.id(), req.password());
        sessions.clear(res);
    }

    // ------------------------------------------------------------ Google

    public record GoogleLogin(String accessToken, Boolean remember) {}

    /**
     * Logowanie przez Google (przepływ tokenu GIS). Token jest weryfikowany bezpośrednio w Google:
     * musi być wydany dla NASZEGO klienta (aud), a adres e-mail musi być zweryfikowany.
     */
    @PostMapping("/google")
    @SuppressWarnings("unchecked")
    public SessionUser google(@RequestBody GoogleLogin req, HttpServletResponse res) {
        if (!props.auth().googleEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Logowanie przez Google nie jest skonfigurowane. Ustaw zmienną GOOGLE_CLIENT_ID w backendzie.");
        }
        if (req.accessToken() == null || req.accessToken().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Brak tokenu Google.");
        Map<String, Object> info;
        Map<String, Object> user;
        try {
            info = google.get().uri("https://oauth2.googleapis.com/tokeninfo?access_token={t}", req.accessToken())
                    .retrieve().body(Map.class);
            user = google.get().uri("https://openidconnect.googleapis.com/v1/userinfo")
                    .header("Authorization", "Bearer " + req.accessToken()).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Google odrzucił token logowania. Spróbuj ponownie.");
        }
        String aud = String.valueOf(info == null ? null : info.getOrDefault("aud", info.get("azp")));
        if (!props.auth().googleClientId().equals(aud)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token Google został wydany dla innej aplikacji.");
        }
        if (user == null || user.get("sub") == null || user.get("email") == null
                || !Boolean.parseBoolean(String.valueOf(user.get("email_verified")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Konto Google nie ma zweryfikowanego adresu e-mail.");
        }
        User u = auth.loginWithGoogle(String.valueOf(user.get("sub")), String.valueOf(user.get("email")),
                user.get("name") == null ? null : String.valueOf(user.get("name")),
                user.get("picture") == null ? null : String.valueOf(user.get("picture")));
        sessions.write(res, u, !Boolean.FALSE.equals(req.remember()));
        return SessionUser.of(u);
    }
}
