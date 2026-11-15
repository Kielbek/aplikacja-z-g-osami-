package ai.voicecraft.audio;

import ai.voicecraft.config.AppProperties;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {
    @Bean
    AudioStorage audioStorage(AppProperties props) {
        String type = props.storage().type() == null ? "local" : props.storage().type().toLowerCase();
        var log = LoggerFactory.getLogger(StorageConfig.class);
        if ("s3".equals(type)) {
            log.info("Magazyn audio: S3 (bucket {})", props.storage().s3().bucket());
            return new S3AudioStorage(props.storage().s3());
        }
        log.info("Magazyn audio: lokalny katalog {}", props.dataDir().resolve("audio").toAbsolutePath());
        return new LocalAudioStorage(props.dataDir().resolve("audio"));
    }
}
