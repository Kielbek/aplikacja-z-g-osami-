package ai.voicecraft.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voicecraft")
public record AppProperties(Path dataDir, List<String> corsOrigins, Engine engine, boolean seedDemoData) {
    public record Engine(String url, Duration connectTimeout, Duration readTimeout) {}
}
