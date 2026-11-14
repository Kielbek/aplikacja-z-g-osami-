package ai.voicecraft.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voicecraft")
public record AppProperties(Path dataDir, List<String> corsOrigins, Engine engine, boolean seedDemoData, Auth auth) {
    public record Engine(String url, Duration connectTimeout, Duration readTimeout) {}

    /** googleClientId: identyfikator klienta OAuth 2.0 (Google Cloud Console → Dane logowania). */
    public record Auth(String googleClientId) {
        public boolean googleEnabled() {
            return googleClientId != null && !googleClientId.isBlank();
        }
    }

    public Auth auth() {
        return auth == null ? new Auth(null) : auth;
    }
}
