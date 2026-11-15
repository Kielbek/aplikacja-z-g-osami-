package ai.voicecraft.voice;

import ai.voicecraft.config.AppProperties;
import ai.voicecraft.engine.EngineClient;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Opcja B: jednorazowe wygenerowanie gotowych próbek dla całej biblioteki — każdy głos × 30 języków —
 * do katalogu {@code voicecraft.voices.samples-dir}/{voice_id}/{lang}.wav. Uruchamiane przy
 * PREGENERATE_SAMPLES=true na maszynie z VoxCPM2; istniejące pliki są pomijane (można przerwać i wznowić).
 * Gotowy katalog wystarczy potem skopiować na serwer produkcyjny — odsłuch próbek nie czeka na API.
 */
@Component
public class SamplePregenerator {
    private static final Logger log = LoggerFactory.getLogger(SamplePregenerator.class);
    private final AppProperties props;
    private final VoiceRepository voices;
    private final VoicePreviewService previews;
    private final EngineClient engine;

    public SamplePregenerator(AppProperties props, VoiceRepository voices, VoicePreviewService previews, EngineClient engine) {
        this.props = props;
        this.voices = voices;
        this.previews = previews;
        this.engine = engine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.voices().pregenerateSamples()) return;
        Thread.ofVirtual().name("sample-pregenerator").start(this::run);
    }

    void run() {
        var health = engine.health(true);
        if (health == null || !"voxcpm2".equals(health.mode())) {
            log.warn("PREGENERATE_SAMPLES=true, ale silnik nie działa w trybie VoxCPM2 ({}). Próbki nie zostaną wygenerowane.",
                    health == null ? "offline" : health.mode());
            return;
        }
        Path dir = previews.samplesDir();
        int done = 0, skipped = 0, failed = 0;
        var library = voices.findAll().stream().filter(v -> v.ownerId == null).toList();
        log.info("Generowanie próbek: {} głosów × {} języków → {}", library.size(), PreviewTexts.TEXTS.size(), dir);
        for (Voice v : library) {
            for (String lang : PreviewTexts.TEXTS.keySet()) {
                Path target = dir.resolve(v.id).resolve(lang + ".wav");
                if (Files.isRegularFile(target)) {
                    skipped++;
                    continue;
                }
                try {
                    var audio = previews.synthesize(v, lang);
                    Files.createDirectories(target.getParent());
                    Path tmp = target.resolveSibling(lang + ".wav.part");
                    Files.write(tmp, audio.wav());
                    Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    done++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Próbka {}/{} nie powstała: {}", v.id, lang, e.getMessage());
                }
            }
            log.info("Próbki głosu {} gotowe ({} nowych, {} pominiętych, {} błędów)", v.id, done, skipped, failed);
        }
        log.info("Generowanie próbek zakończone: {} nowych, {} już istniało, {} błędów", done, skipped, failed);
    }
}
