package ai.voicecraft.security;

import ai.voicecraft.config.AppProperties;
import ai.voicecraft.user.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/**
 * Wydawanie i odczyt sesji. Sesja = JWT (HS256) w ciasteczku httpOnly „vc_session”
 * (SameSite=Lax, opcjonalnie Secure). Token zawiera wersję („ver”), którą można unieważnić.
 */
@Component
public class SessionTokens {
    public static final String COOKIE = "vc_session";
    private static final Logger log = LoggerFactory.getLogger(SessionTokens.class);

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AppProperties props;

    public SessionTokens(AppProperties props, Environment env) {
        this.props = props;
        SecretKey key = new SecretKeySpec(resolveSecret(props, env), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public String issue(User user, Duration ttl) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("voicecraft")
                .subject(user.id)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("ver", user.tokenVersion)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Ustawia ciasteczko sesji. remember=false → ciasteczko sesyjne przeglądarki. */
    public void write(HttpServletResponse res, User user, boolean remember) {
        Duration ttl = props.auth().sessionTtl() == null ? Duration.ofDays(14) : props.auth().sessionTtl();
        var cookie = ResponseCookie.from(COOKIE, issue(user, remember ? ttl : Duration.ofHours(12)))
                .httpOnly(true)
                .secure(props.auth().cookieSecure())
                .sameSite("Lax")
                .path("/");
        if (remember) cookie.maxAge(ttl);
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }

    public void clear(HttpServletResponse res) {
        var cookie = ResponseCookie.from(COOKIE, "").httpOnly(true).secure(props.auth().cookieSecure())
                .sameSite("Lax").path("/").maxAge(0).build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static byte[] resolveSecret(AppProperties props, Environment env) {
        String configured = props.auth().jwtSecret();
        if (configured != null && !configured.isBlank()) {
            if (configured.length() < 32) throw new IllegalStateException("JWT_SECRET musi mieć co najmniej 32 znaki.");
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))) {
            throw new IllegalStateException("Ustaw JWT_SECRET (min. 32 znaki) w środowisku produkcyjnym.");
        }
        // Środowisko deweloperskie: sekret generowany raz i zapisywany w katalogu danych
        Path file = props.dataDir().resolve("jwt-secret");
        try {
            if (Files.exists(file)) return Base64.getDecoder().decode(Files.readString(file).trim());
            byte[] secret = new byte[48];
            new SecureRandom().nextBytes(secret);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(secret));
            log.warn("Brak JWT_SECRET — wygenerowano sekret deweloperski w {}", file.toAbsolutePath());
            return secret;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
