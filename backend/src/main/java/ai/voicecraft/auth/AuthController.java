package ai.voicecraft.auth;

import ai.voicecraft.account.AccountService;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Logowanie przez Google (Google Identity Services, przepływ tokenu OAuth 2.0).
 * Frontend uzyskuje access token, a backend weryfikuje go bezpośrednio w Google:
 * sprawdza, czy token wydano dla NASZEGO klienta (aud) i czy e-mail jest zweryfikowany.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AppProperties props;
    private final AccountService account;
    private final RestClient google = RestClient.create();

    public AuthController(AppProperties props, AccountService account) {
        this.props = props;
        this.account = account;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        var out = new LinkedHashMap<String, Object>();
        out.put("googleEnabled", props.auth().googleEnabled());
        out.put("googleClientId", props.auth().googleEnabled() ? props.auth().googleClientId() : null);
        return out;
    }

    public record GoogleLogin(String accessToken) {}

    @PostMapping("/google")
    @SuppressWarnings("unchecked")
    public Map<String, Object> google(@RequestBody GoogleLogin req) {
        if (!props.auth().googleEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Logowanie przez Google nie jest skonfigurowane. Ustaw zmienną GOOGLE_CLIENT_ID w backendzie.");
        }
        if (req.accessToken() == null || req.accessToken().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Brak tokenu Google.");
        }
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
        if (user == null || user.get("email") == null || !Boolean.parseBoolean(String.valueOf(user.get("email_verified")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Konto Google nie ma zweryfikowanego adresu e-mail.");
        }
        String email = String.valueOf(user.get("email"));
        String name = user.get("name") == null ? email : String.valueOf(user.get("name"));
        // Wersja z jednym użytkownikiem: profil konta przejmuje dane z Google
        account.update(Map.of("email", email, "fullName", name));
        var out = new LinkedHashMap<String, Object>();
        out.put("email", email);
        out.put("name", name);
        out.put("picture", user.get("picture"));
        out.put("provider", "google");
        return out;
    }
}
