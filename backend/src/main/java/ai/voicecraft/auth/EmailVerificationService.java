package ai.voicecraft.auth;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import ai.voicecraft.user.EmailVerificationToken;
import ai.voicecraft.user.EmailVerificationTokenRepository;
import ai.voicecraft.user.User;
import ai.voicecraft.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Potwierdzanie adresu e-mail. Bez potwierdzenia konto może się zalogować i edytować projekty,
 * ale nie generuje audio ani nie klonuje głosu — utrudnia to zakładanie wielu darmowych kont.
 */
@Service
public class EmailVerificationService {
    static final Duration TTL = Duration.ofHours(48);
    static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);

    private final UserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final MailService mail;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    public EmailVerificationService(UserRepository users, EmailVerificationTokenRepository tokens, MailService mail,
                                    AppProperties props) {
        this.users = users;
        this.tokens = tokens;
        this.mail = mail;
        this.props = props;
    }

    public boolean required() {
        return props.auth().emailVerificationRequired();
    }

    /** Wysyła link potwierdzający na bieżący adres konta (poprzednie linki przestają działać). */
    @Transactional
    public void send(User u) {
        if (u.emailVerifiedAt != null) return;
        tokens.deleteByUserId(u.id);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var t = new EmailVerificationToken();
        t.tokenHash = sha256(token);
        t.userId = u.id;
        t.email = u.email;
        t.expiresAt = Instant.now().plus(TTL);
        tokens.save(t);
        lastSent.put(u.id, Instant.now());
        String link = props.publicUrl() + "/verify-email?token=" + token;
        mail.send(u.email, "Narrivy — potwierdź adres e-mail",
                "Dzień dobry,\n\npotwierdź adres e-mail, aby zacząć generować nagrania w Narrivy.\n"
                        + "Link jest ważny 48 godzin:\n\n" + link
                        + "\n\nJeśli to nie Ty zakładałeś konto, zignoruj tę wiadomość.");
    }

    /** Ponowna wysyłka na żądanie użytkownika — najwyżej raz na minutę. */
    @Transactional
    public void resend(String userId) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Konto"));
        if (u.emailVerifiedAt != null) return;
        Instant prev = lastSent.get(userId);
        if (prev != null && prev.plus(RESEND_INTERVAL).isAfter(Instant.now())) {
            long s = Math.max(1, Duration.between(Instant.now(), prev.plus(RESEND_INTERVAL)).toSeconds());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Wiadomość została właśnie wysłana. Spróbuj ponownie za " + s + " s.");
        }
        send(u);
    }

    @Transactional
    public User confirm(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Brak tokenu.");
        var t = tokens.findById(sha256(token))
                .filter(x -> x.usedAt == null && x.expiresAt.isAfter(Instant.now()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "Link wygasł lub został już użyty. Zaloguj się i wyślij nowy link."));
        User u = users.findById(t.userId).orElseThrow(() -> ApiException.notFound("Konto"));
        if (!u.email.equalsIgnoreCase(t.email)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ten link dotyczy poprzedniego adresu e-mail. Wyślij nowy link.");
        }
        t.usedAt = Instant.now();
        if (u.emailVerifiedAt == null) u.emailVerifiedAt = Instant.now();
        return u;
    }

    /** Po zmianie adresu konto wymaga ponownego potwierdzenia. */
    @Transactional
    public void emailChanged(User u) {
        u.emailVerifiedAt = null;
        send(u);
    }

    public void requireVerified(String userId) {
        if (!required()) return;
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Konto"));
        if (u.emailVerifiedAt == null) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Potwierdź adres e-mail, aby generować nagrania. Link wysłaliśmy na " + u.email + ".", "EMAIL_NOT_VERIFIED");
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
