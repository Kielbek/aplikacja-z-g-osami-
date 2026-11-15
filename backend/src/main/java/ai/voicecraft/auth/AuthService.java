package ai.voicecraft.auth;

import ai.voicecraft.account.Account;
import ai.voicecraft.account.AccountRepository;
import ai.voicecraft.account.AccountService;
import ai.voicecraft.account.SubscriptionPlan;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import ai.voicecraft.history.HistoryRepository;
import ai.voicecraft.jobs.JobRepository;
import ai.voicecraft.pronunciation.PronunciationRepository;
import ai.voicecraft.project.ProjectService;
import ai.voicecraft.security.LoginRateLimiter;
import ai.voicecraft.user.PasswordResetToken;
import ai.voicecraft.user.PasswordResetTokenRepository;
import ai.voicecraft.user.User;
import ai.voicecraft.user.UserRepository;
import ai.voicecraft.voice.VoiceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    static final int MIN_PASSWORD = 8;
    private static final Duration RESET_TTL = Duration.ofHours(1);
    /** Hash używany, gdy konto nie istnieje — wyrównuje czas odpowiedzi (brak wycieku istnienia konta). */
    private final String dummyHash;

    private final UserRepository users;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final ProjectService projects;
    private final VoiceService voices;
    private final HistoryRepository history;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    private final MailService mail;
    private final AppProperties props;
    private final JobRepository jobs;
    private final PronunciationRepository dictionary;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, AccountRepository accounts, AccountService accountService, ProjectService projects,
                       VoiceService voices, HistoryRepository history, PasswordResetTokenRepository resetTokens,
                       PasswordEncoder encoder, LoginRateLimiter limiter, MailService mail, AppProperties props,
                       JobRepository jobs, PronunciationRepository dictionary) {
        this.jobs = jobs;
        this.dictionary = dictionary;
        this.users = users;
        this.accounts = accounts;
        this.accountService = accountService;
        this.projects = projects;
        this.voices = voices;
        this.history = history;
        this.resetTokens = resetTokens;
        this.encoder = encoder;
        this.limiter = limiter;
        this.mail = mail;
        this.props = props;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------ rejestracja i logowanie

    @Transactional
    public User register(String name, String email, String password) {
        String normalized = normalizeEmail(email);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Podaj imię i nazwisko.");
        validatePassword(password);
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new ApiException(HttpStatus.CONFLICT, "Konto z tym adresem e-mail już istnieje. Zaloguj się lub zresetuj hasło.");
        }
        var u = newUser(normalized, name.trim());
        u.passwordHash = encoder.encode(password);
        users.save(u);
        accounts.save(AccountService.create(u, SubscriptionPlan.FREE));
        projects.createWelcomeProject(u.id);
        return u;
    }

    @Transactional
    public User login(String email, String password, String ip) {
        String normalized = normalizeEmail(email);
        limiter.check(normalized, ip);
        var user = users.findByEmailIgnoreCase(normalized).orElse(null);
        boolean ok = user != null && user.passwordHash != null && encoder.matches(password == null ? "" : password, user.passwordHash);
        if (user == null || user.passwordHash == null) encoder.matches("x", dummyHash);
        if (!ok) {
            limiter.recordFailure(normalized, ip);
            if (user != null && user.passwordHash == null) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "To konto używa logowania przez Google.");
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Nieprawidłowy e-mail lub hasło.");
        }
        limiter.recordSuccess(normalized, ip);
        user.lastLoginAt = Instant.now();
        return user;
    }

    /** Logowanie / rejestracja przez Google (dane zweryfikowane wcześniej w Google). */
    @Transactional
    public User loginWithGoogle(String sub, String email, String name, String picture) {
        String normalized = normalizeEmail(email);
        var user = users.findByGoogleSub(sub).or(() -> users.findByEmailIgnoreCase(normalized)).orElse(null);
        if (user == null) {
            user = newUser(normalized, name);
            user.googleSub = sub;
            user.pictureUrl = picture;
            users.save(user);
            accounts.save(AccountService.create(user, SubscriptionPlan.FREE));
            projects.createWelcomeProject(user.id);
        } else {
            // Połączenie istniejącego konta e-mail z Google (e-mail zweryfikowany przez Google)
            if (user.googleSub == null) user.googleSub = sub;
            if (picture != null) user.pictureUrl = picture;
        }
        user.lastLoginAt = Instant.now();
        return user;
    }

    // ------------------------------------------------------------ sesje i hasło

    @Transactional
    public User invalidateSessions(String userId) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Konto"));
        u.tokenVersion++;
        return u;
    }

    @Transactional
    public User changePassword(String userId, String current, String next) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Konto"));
        if (u.passwordHash != null && (current == null || !encoder.matches(current, u.passwordHash))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Obecne hasło jest nieprawidłowe.");
        }
        validatePassword(next);
        u.passwordHash = encoder.encode(next);
        u.tokenVersion++; // wylogowanie pozostałych urządzeń
        return u;
    }

    /** Zawsze kończy się sukcesem — nie ujawnia, czy konto istnieje. */
    @Transactional
    public void requestPasswordReset(String email, String ip) {
        String normalized;
        try {
            normalized = normalizeEmail(email);
        } catch (IllegalArgumentException e) {
            return;
        }
        limiter.check("reset:" + normalized, ip);
        limiter.recordFailure("reset:" + normalized, ip); // ogranicza liczbę wysyłanych e-maili
        users.findByEmailIgnoreCase(normalized).ifPresent(u -> {
            resetTokens.deleteByUserId(u.id);
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            var t = new PasswordResetToken();
            t.tokenHash = sha256(token);
            t.userId = u.id;
            t.expiresAt = Instant.now().plus(RESET_TTL);
            resetTokens.save(t);
            String link = props.publicUrl() + "/reset-password?token=" + token;
            mail.send(u.email, "VoiceCraft AI — ustaw nowe hasło",
                    "Dzień dobry,\n\notrzymaliśmy prośbę o zmianę hasła do konta VoiceCraft AI.\n"
                            + "Kliknij link, aby ustawić nowe hasło (ważny 1 godzinę):\n\n" + link
                            + "\n\nJeśli to nie Ty, zignoruj tę wiadomość — hasło pozostanie bez zmian.");
        });
    }

    @Transactional
    public User confirmPasswordReset(String token, String password) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Brak tokenu.");
        var t = resetTokens.findById(sha256(token))
                .filter(x -> x.usedAt == null && x.expiresAt.isAfter(Instant.now()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Link wygasł lub został już użyty. Poproś o nowy."));
        validatePassword(password);
        User u = users.findById(t.userId).orElseThrow(() -> ApiException.notFound("Konto"));
        u.passwordHash = encoder.encode(password);
        u.tokenVersion++;
        t.usedAt = Instant.now();
        return u;
    }

    /** Trwałe usunięcie konta i danych użytkownika. */
    @Transactional
    public void deleteAccount(String userId, String password) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Konto"));
        if (u.passwordHash != null && (password == null || !encoder.matches(password, u.passwordHash))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Hasło jest nieprawidłowe.");
        }
        Account a = accounts.findById(userId).orElse(null);
        if (a != null && a.stripeSubscriptionId != null && "active".equals(a.subscriptionStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "Najpierw anuluj aktywną subskrypcję w panelu płatności.");
        }
        jobs.deleteByUserId(userId);
        dictionary.deleteByOwnerId(userId);
        projects.deleteAllOf(userId);
        voices.deleteAllOf(userId);
        history.deleteByUserId(userId);
        resetTokens.deleteByUserId(userId);
        if (a != null) accounts.delete(a);
        users.delete(u);
    }

    public User get(String userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Konto nie istnieje."));
    }

    public AccountService accounts() {
        return accountService;
    }

    // ------------------------------------------------------------ pomocnicze

    private static User newUser(String email, String name) {
        var u = new User();
        u.id = UUID.randomUUID().toString();
        u.email = email;
        u.fullName = name == null || name.isBlank() ? email : name;
        return u;
    }

    static String normalizeEmail(String email) {
        if (email == null || !email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Podaj poprawny adres e-mail.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("Hasło musi mieć co najmniej " + MIN_PASSWORD + " znaków.");
        }
        if (password.length() > 128) throw new IllegalArgumentException("Hasło może mieć najwyżej 128 znaków.");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
