package ai.voicecraft.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voicecraft")
public record AppProperties(Path dataDir, List<String> corsOrigins, Engine engine, boolean seedDemoData, Auth auth,
                            Storage storage, Billing billing, String publicUrl, String mailFrom) {

    public record Engine(String url, Duration connectTimeout, Duration readTimeout) {}

    /**
     * googleClientId — identyfikator klienta OAuth 2.0 (Google Cloud Console).
     * jwtSecret — sekret HMAC do podpisywania sesji (min. 32 znaki; w produkcji obowiązkowy).
     */
    public record Auth(String googleClientId, String jwtSecret, Duration sessionTtl, boolean cookieSecure) {
        public boolean googleEnabled() {
            return googleClientId != null && !googleClientId.isBlank();
        }
    }

    /** type: local | s3. Dla S3 zgodnych usług (MinIO, R2) ustaw endpoint i pathStyle. */
    public record Storage(String type, S3 s3) {
        public record S3(String bucket, String region, String endpoint, String accessKey, String secretKey, boolean pathStyle) {}
    }

    /**
     * Płatności Stripe. Gdy brak stripeSecretKey — płatności wyłączone.
     * demoPlanSwitch pozwala zmieniać plan bez płatności (tylko środowisko deweloperskie!).
     */
    public record Billing(String stripeSecretKey, String stripeWebhookSecret, Map<String, String> prices, boolean demoPlanSwitch) {
        public boolean stripeEnabled() {
            return stripeSecretKey != null && !stripeSecretKey.isBlank();
        }
    }

    public Auth auth() {
        return auth == null ? new Auth(null, null, Duration.ofDays(14), false) : auth;
    }

    public Storage storage() {
        return storage == null ? new Storage("local", null) : storage;
    }

    public Billing billing() {
        return billing == null ? new Billing(null, null, Map.of(), false) : billing;
    }
}
