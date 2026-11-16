package ai.voicecraft.voice;

import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import ai.voicecraft.engine.EngineDtos.Audio;
import ai.voicecraft.project.GenerationService;
import ai.voicecraft.project.VoiceSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Próbki odsłuchowe głosów w wybranym języku.
 * <ol>
 *   <li><b>Opcja B (statyczna):</b> gotowy plik {samplesDir}/{voice_id}/{lang}.wav — odtwarzany natychmiast.</li>
 *   <li><b>Opcja A (dynamiczna):</b> brak pliku — próbka jest generowana raz (VoxCPM2, nagranie wzorcowe głosu,
 *       natywny akcent języka docelowego) i zapamiętywana; kolejne odsłuchy są natychmiastowe.</li>
 * </ol>
 * Próbki nie zużywają limitu znaków użytkownika.
 */
@Service
public class VoicePreviewService {
    private final VoiceService voices;
    private final VoicePreviewRepository previews;
    private final GenerationService generation;
    private final AudioStorage storage;
    private final Path samplesDir;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public VoicePreviewService(VoiceService voices, VoicePreviewRepository previews, GenerationService generation,
                               AudioStorage storage, AppProperties props) {
        this.voices = voices;
        this.previews = previews;
        this.generation = generation;
        this.storage = storage;
        this.samplesDir = props.voices().samplesDir().toAbsolutePath().normalize();
    }

    public record Preview(byte[] wav, String engineMode, String source) {}

    public Preview get(String voiceId, String lang) {
        if (!PreviewTexts.supported(lang)) throw new ApiException(HttpStatus.BAD_REQUEST, "Nieobsługiwany język próbki: " + lang);
        Voice v = voices.get(voiceId); // widoczność: biblioteka lub własny głos

        Path file = staticFile(v.id, lang);
        if (file != null) {
            try {
                return new Preview(Files.readAllBytes(file), "voxcpm2", "static");
            } catch (IOException ignored) {
                // przejdź do pamięci podręcznej / generowania
            }
        }
        String mode = generation.currentMode();
        var cached = previews.findById(new VoicePreview.Key(v.id, lang)).orElse(null);
        if (cached != null && storage.exists(cached.audioFile) && (mode == null || mode.equals(cached.engineMode))) {
            return new Preview(storage.read(cached.audioFile), cached.engineMode, "cache");
        }
        synchronized (locks.computeIfAbsent(v.id + "|" + lang, k -> new Object())) {
            cached = previews.findById(new VoicePreview.Key(v.id, lang)).orElse(null);
            if (cached != null && storage.exists(cached.audioFile) && mode != null && mode.equals(cached.engineMode)) {
                return new Preview(storage.read(cached.audioFile), cached.engineMode, "cache");
            }
            Audio a = synthesize(v, lang);
            var p = cached != null ? cached : new VoicePreview();
            p.voiceId = v.id;
            p.language = lang;
            p.engineMode = a.engineMode();
            p.audioFile = storage.saveFor(null, "preview", a.wav()); // próbka wspólna dla wszystkich
            p.createdAt = java.time.Instant.now();
            previews.save(p);
            return new Preview(a.wav(), a.engineMode(), "generated");
        }
    }

    /** Synteza zdania próbki w języku docelowym (bez „ultimate cloning” — natywny akcent). */
    public Audio synthesize(Voice v, String lang) {
        var s = new VoiceSettings();
        s.language = lang;
        s.voiceId = v.id;
        return generation.synthesize(PreviewTexts.TEXTS.get(lang), s, v);
    }

    /** Ścieżka gotowej próbki (opcja B) lub null. */
    public Path staticFile(String voiceId, String lang) {
        if (!voiceId.matches("[A-Za-z0-9_-]+") || !PreviewTexts.supported(lang)) return null;
        Path f = samplesDir.resolve(voiceId).resolve(lang + ".wav").normalize();
        return f.startsWith(samplesDir) && Files.isRegularFile(f) ? f : null;
    }

    public Path samplesDir() {
        return samplesDir;
    }
}
