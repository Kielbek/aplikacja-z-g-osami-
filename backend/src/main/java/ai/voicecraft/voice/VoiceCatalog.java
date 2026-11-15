package ai.voicecraft.voice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Katalog głosów biblioteki (resources/voices/catalog.json) — jedno źródło prawdy dla backendu i skryptów. */
@Component
public class VoiceCatalog {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(String id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String id, String name, String persona, String gender, int age, String category, String reference,
                        String referenceText, List<String> traits, String description, String designPrompt, String instruction) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Catalog(List<Category> categories, List<Entry> voices) {}

    private final Catalog catalog;

    public VoiceCatalog(JsonMapper json) {
        try (InputStream in = new ClassPathResource("voices/catalog.json").getInputStream()) {
            this.catalog = json.readValue(in, Catalog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Category> categories() {
        return catalog.categories();
    }

    public List<Entry> voices() {
        return catalog.voices();
    }
}
