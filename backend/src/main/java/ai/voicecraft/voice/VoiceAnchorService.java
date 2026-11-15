package ai.voicecraft.voice;

import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos.*;
import ai.voicecraft.project.GenerationService;
import ai.voicecraft.project.VoiceSettings;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Nagrania wzorcowe głosów — gwarancja, że każdy fragment projektu brzmi tym samym głosem.
 *
 * <p>Samo projektowanie głosu z opisu (voice design) daje przy każdym wywołaniu nieco inną barwę.
 * Dlatego każdy głos dostaje jedno nagranie wzorcowe, a synteza fragmentów odbywa się przez klonowanie
 * z tego nagrania (VoxCPM2: reference_wav_path + prompt_wav_path/prompt_text):
 * <ul>
 *   <li>głos sklonowany — przesłane nagranie,</li>
 *   <li>głos zaprojektowany — wariant wybrany przez użytkownika podczas projektowania,</li>
 *   <li>głos z biblioteki (lub projekt bez wariantu) — próbka wygenerowana raz, ze stałym seedem.</li>
 * </ul>
 * Nagranie jest przechowywane w magazynie aplikacji, więc po restarcie silnika lub utracie jego danych
 * referencja jest rejestrowana ponownie pod tym samym identyfikatorem.
 *
 * <p>W trybie demonstracyjnym (silnik bez klonowania) głos opisuje wyłącznie opis — bez nagrania wzorcowego.
 */
@Service
public class VoiceAnchorService {
    private static final Logger log = LoggerFactory.getLogger(VoiceAnchorService.class);
    public static final String MISSING_PREFIX = "REFERENCE_MISSING";

    private final VoiceRepository voices;
    private final EngineClient engine;
    private final AudioStorage storage;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public VoiceAnchorService(VoiceRepository voices, EngineClient engine, AudioStorage storage) {
        this.voices = voices;
        this.engine = engine;
        this.storage = storage;
    }

    /** Silnik potrafi syntezować z nagrania referencyjnego (prawdziwy model, nie tryb demo). */
    public boolean supported(Health h) {
        return h != null && h.capabilities() != null && h.capabilities().cloning();
    }

    /**
     * Identyfikator referencji w silniku dla głosu; tworzy nagranie wzorcowe przy pierwszym użyciu.
     * Zwraca null, gdy silnik nie obsługuje referencji (tryb demo) — wtedy głos opisuje sam opis.
     */
    public String ensure(Voice voice, Health health) {
        if (!supported(health)) return null;
        if (voice.referenceId != null) return voice.referenceId;
        synchronized (locks.computeIfAbsent(voice.id, k -> new Object())) {
            Voice v = voices.findById(voice.id).orElse(voice);
            if (v.referenceId == null) {
                // Wspólna barwa z katalogu (np. wariant „Relaks”): użyj nagrania wzorcowego głosu bazowego
                if ((v.referenceFile == null || !storage.exists(v.referenceFile)) && v.referenceKey != null) {
                    voices.findFirstByReferenceKeyAndReferenceFileIsNotNullAndIdNot(v.referenceKey, v.id)
                            .filter(o -> storage.exists(o.referenceFile))
                            .ifPresent(o -> {
                                v.referenceFile = o.referenceFile;
                                v.referenceText = o.referenceText;
                            });
                }
                if (v.referenceFile != null && storage.exists(v.referenceFile)) {
                    register(v, storage.read(v.referenceFile));
                } else if (v.enginePrompt != null && !v.enginePrompt.isBlank()) {
                    createAnchor(v);
                } else {
                    return null;
                }
                voices.save(v);
            }
            copy(v, voice);
            return v.referenceId;
        }
    }

    /**
     * Silnik zgłosił brak referencji (np. nowy kontener bez danych) — rejestruje ją ponownie
     * z nagrania w magazynie aplikacji. Zwraca true, gdy można powtórzyć syntezę.
     */
    public boolean recover(Voice voice) {
        synchronized (locks.computeIfAbsent(voice.id, k -> new Object())) {
            Voice v = voices.findById(voice.id).orElse(voice);
            if (v.referenceFile != null && storage.exists(v.referenceFile)) {
                log.info("Ponowna rejestracja nagrania wzorcowego głosu {} w silniku", v.id);
                register(v, storage.read(v.referenceFile));
                voices.save(v);
                copy(v, voice);
                return true;
            }
            if (v.kind == VoiceKind.CLONED) {
                throw new ApiException(HttpStatus.CONFLICT, "Silnik utracił nagranie referencyjne głosu „" + v.name
                        + "”, a aplikacja nie ma jego kopii. Utwórz klon ponownie z oryginalnego nagrania.");
            }
            // Głos z opisu bez zachowanego nagrania — wygeneruj nowe nagranie wzorcowe
            v.referenceId = null;
            createAnchor(v);
            voices.save(v);
            copy(v, voice);
            return true;
        }
    }

    public static boolean isMissingReference(ApiException e) {
        return e.status().value() == 409 && e.getMessage() != null && e.getMessage().startsWith(MISSING_PREFIX);
    }

    /** Stały seed wynikający z identyfikatora głosu — to samo nagranie wzorcowe przy ponownym utworzeniu. */
    static int anchorSeed(String voiceId) {
        return Math.floorMod(voiceId.hashCode(), 999_983) + 1;
    }

    static String anchorText(Voice v) {
        if (v.sampleText != null && v.sampleText.trim().length() >= 20) return v.sampleText.trim();
        String lang = v.languages == null || v.languages.isEmpty() ? "pl" : v.languages.getFirst();
        return switch (lang) {
            case "en" -> "Hello! This is a reference recording that defines how this voice sounds in every project.";
            case "de" -> "Guten Tag! Dies ist eine Referenzaufnahme, die den Klang dieser Stimme in jedem Projekt festlegt.";
            default -> "Dzień dobry! To jest nagranie wzorcowe, które określa brzmienie tego głosu we wszystkich projektach.";
        };
    }

    private void createAnchor(Voice v) {
        var s = new VoiceSettings();
        s.seed = anchorSeed(v.id);
        String text = anchorText(v);
        String lang = v.languages == null || v.languages.isEmpty() ? "pl" : v.languages.getFirst();
        log.info("Tworzenie nagrania wzorcowego głosu {} (seed {})", v.id, s.seed);
        Audio a = engine.design(new VoiceDesignRequest(v.enginePrompt, text, lang, GenerationService.params(s)));
        v.referenceFile = storage.save("ref", a.wav());
        v.referenceText = text;
        register(v, a.wav());
        // Próbka głosu = nagranie wzorcowe: to, co słychać w bibliotece, brzmi jak fragmenty projektu
        if (v.sampleText == null || text.equals(v.sampleText.trim())) {
            v.sampleFile = v.referenceFile;
            v.sampleEngineMode = a.engineMode();
        }
    }

    private void register(Voice v, byte[] wav) {
        String id = v.referenceId != null ? v.referenceId : "anchor-" + v.id.replaceAll("[^A-Za-z0-9_-]", "");
        CloneReference ref = engine.createReference(wav, "reference.wav", v.referenceText, true, id, true);
        v.referenceId = ref.referenceId();
    }

    private static void copy(Voice from, Voice to) {
        if (from == to) return;
        to.referenceId = from.referenceId;
        to.referenceFile = from.referenceFile;
        to.referenceText = from.referenceText;
        to.sampleFile = from.sampleFile;
        to.sampleEngineMode = from.sampleEngineMode;
    }
}
