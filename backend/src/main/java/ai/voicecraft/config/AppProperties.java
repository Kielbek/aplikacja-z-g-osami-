package ai.voicecraft.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voicecraft")
public record AppProperties(Path dataDir, List<String> corsOrigins, Engine engine, boolean seedDemoData, Auth auth,
                            Storage storage, Billing billing, String publicUrl, String mailFrom,
                            Voices voices) {

    /** apiKey — klucz silnika (ENGINE_API_KEY), wymagany, gdy silnik działa poza siecią prywatną (np. wynajęte GPU). */
    public record Engine(String url, Duration connectTimeout, Duration readTimeout, String apiKey) {}

    /**
     * googleClientId — identyfikator klienta OAuth 2.0 (Google Cloud Console).
     * jwtSecret — sekret HMAC do podpisywania sesji (min. 32 znaki; w produkcji obowiązkowy).
     */
    public record Auth(String googleClientId, String jwtSecret, Duration sessionTtl, boolean cookieSecure,
                       Boolean requireEmailVerification) {
        public boolean googleEnabled() {
            return googleClientId != null && !googleClientId.isBlank();
        }

        /** Generowanie i klonowanie wymagają potwierdzonego adresu e-mail (domyślnie tak). */
        public boolean emailVerificationRequired() {
            return !Boolean.FALSE.equals(requireEmailVerification);
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

    /**
     * promptsDir — nagrania wzorcowe (Master Prompt) z katalogu głosów, np. prompts/agnieszka_warm.wav.
     * samplesDir — gotowe próbki odsłuchowe {voice_id}/{lang}.wav (opcja B, odtwarzane bez czekania).
     * pregenerateSamples — przy starcie wygeneruj brakujące próbki wszystkich głosów biblioteki (wymaga VoxCPM2).
     */
    public record Voices(Path promptsDir, Path samplesDir, boolean pregenerateSamples) {}

    public Voices voices() {
        Path base = dataDir == null ? Path.of("./data") : dataDir;
        if (voices == null) return new Voices(base.resolve("voices"), base.resolve("voices/samples"), false);
        return new Voices(voices.promptsDir() == null ? base.resolve("voices") : voices.promptsDir(),
                voices.samplesDir() == null ? base.resolve("voices/samples") : voices.samplesDir(), voices.pregenerateSamples());
    }

    public Auth auth() {
        return auth == null ? new Auth(null, null, Duration.ofDays(14), false, true) : auth;
    }

    public Storage storage() {
        return storage == null ? new Storage("local", null) : storage;
    }

    public Billing billing() {
        return billing == null ? new Billing(null, null, Map.of(), false) : billing;
    }
}
