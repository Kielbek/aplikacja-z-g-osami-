package ai.voicecraft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VoiceCraftApplication {
    public static void main(String[] args) {
        SpringApplication.run(VoiceCraftApplication.class, args);
    }
}
