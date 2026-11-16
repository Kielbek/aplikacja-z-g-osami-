package ai.voicecraft.publicapi;

import ai.voicecraft.account.AccountService;
import ai.voicecraft.audio.AiContentLabel;
import ai.voicecraft.audio.WavUtils;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos.Audio;
import ai.voicecraft.engine.GpuQueue;
import ai.voicecraft.project.GenerationService;
import ai.voicecraft.project.SpeechText;
import ai.voicecraft.project.TextSplitter;
import ai.voicecraft.project.VoiceSettings;
import ai.voicecraft.pronunciation.PronunciationRepository;
import ai.voicecraft.security.CurrentUser;
import ai.voicecraft.voice.PreviewTexts;
import ai.voicecraft.voice.Voice;
import ai.voicecraft.voice.VoiceRepository;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Synteza dla publicznego API: walidacja, słownik wymowy użytkownika, podział długiego tekstu na części,
 * limit znaków planu, kolejka GPU, format wyjściowy i oznaczenie treści AI w metadanych pliku.
 */
@Service
public class SpeechService {
    /** Najdłuższy tekst w żądaniu synchronicznym (dłuższe — przez zadania w tle). */
    public static final int MAX_SYNC_CHARACTERS = 5_000;
    /** Przerwa między częściami długiego tekstu. */
    static final int CHUNK_PAUSE_MS = 250;
    static final Set<String> FORMATS = Set.of("mp3", "wav");

    private final GenerationService generation;
    private final EngineClient engine;
    private final VoiceRepository voices;
    private final AccountService accounts;
    private final PronunciationRepository dictionary;

    public SpeechService(GenerationService generation, EngineClient engine, VoiceRepository voices, AccountService accounts,
                         PronunciationRepository dictionary) {
        this.generation = generation;
        this.engine = engine;
        this.voices = voices;
        this.accounts = accounts;
        this.dictionary = dictionary;
    }

    /** Zwalidowane żądanie gotowe do syntezy. */
    public record Prepared(String text, Voice voice, String language, String format, VoiceSettings settings, int characters) {}

    public record Result(byte[] audio, String contentType, String format, long durationMs, String engineMode, int characters) {}

    /** Walidacja żądania dla bieżącego użytkownika. {@code maxCharacters} — limit długości tekstu. */
    public Prepared prepare(String text, String voiceId, String language, String format, SpeechRequest.Settings p, int maxCharacters) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Pole „text” jest wymagane.");
        String clean = text.replace("\r\n", "\n").strip();
        int characters = SpeechText.spokenCharacters(clean);
        if (characters == 0) throw new IllegalArgumentException("Tekst nie zawiera nic do przeczytania.");
        if (characters > maxCharacters) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Tekst ma " + characters + " znaków; limit dla tego wywołania to "
                    + maxCharacters + ".", "text_too_long");
        }
        if (voiceId == null || voiceId.isBlank()) throw new IllegalArgumentException("Pole „voice_id” jest wymagane.");
        String uid = CurrentUser.id();
        Voice voice = voices.findById(voiceId).filter(v -> v.ownerId == null || v.ownerId.equals(uid))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Głos „" + voiceId + "” nie istnieje.", "voice_not_found"));
        String lang = language == null || language.isBlank()
                ? (voice.languages.isEmpty() ? "pl" : voice.languages.getFirst())
                : language.trim().toLowerCase(Locale.ROOT);
        if (!PreviewTexts.TEXTS.containsKey(lang)) {
            throw new IllegalArgumentException("Nieobsługiwany język „" + lang + "”. Obsługiwane: " + String.join(", ", PreviewTexts.TEXTS.keySet()) + ".");
        }
        String fmt = format == null || format.isBlank() ? "mp3" : format.trim().toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(fmt)) throw new IllegalArgumentException("Pole „format” przyjmuje wartości: mp3, wav.");

        var s = new VoiceSettings();
        s.language = lang;
        s.voiceId = voice.id;
        if (p != null) {
            s.style = limit(p.style(), 300, "style");
            s.emotion = limit(p.emotion(), 60, "emotion");
            if (p.speed() != null) {
                if (p.speed() < 0.5 || p.speed() > 2.0) throw new IllegalArgumentException("Pole „speed” musi mieścić się w zakresie 0.5–2.0.");
                s.speed = p.speed();
            }
            if (p.expressiveness() != null) {
                if (p.expressiveness() < 0 || p.expressiveness() > 1) throw new IllegalArgumentException("Pole „expressiveness” musi mieścić się w zakresie 0–1.");
                s.expressiveness = p.expressiveness();
            }
            s.seed = p.seed();
        }
        return new Prepared(clean, voice, lang, fmt, s, characters);
    }

    /**
     * Synteza z naliczeniem znaków. Wywołanie modelu przechodzi przez kolejkę GPU z podanym priorytetem.
     * Znaki są naliczane wyłącznie po udanej syntezie.
     */
    public Result render(Prepared req, String apiKeyId, GpuQueue.Priority priority) {
        String uid = CurrentUser.id();
        accounts.requireGenerationQuota(req.characters());
        String effective = SpeechText.applyDictionary(req.text(), dictionary.findApplicable(uid, null));
        VoiceSettings settings = accounts.effectiveSettings(req.settings());
        Audio audio = GpuQueue.withPriority(priority, () -> {
            var chunks = TextSplitter.segments(effective, TextSplitter.MAX_SEGMENT);
            if (chunks.size() == 1) return generation.synthesizeSegment(chunks.getFirst(), settings, req.voice());
            var wavs = new ArrayList<byte[]>();
            String mode = null;
            for (String chunk : chunks) {
                if (SpeechText.spokenCharacters(chunk) == 0) continue;
                Audio a = generation.synthesizeSegment(chunk, settings, req.voice());
                mode = a.engineMode();
                wavs.add(a.wav());
            }
            byte[] wav = WavUtils.concat(wavs, CHUNK_PAUSE_MS);
            return new Audio(wav, mode, WavUtils.durationMs(wav));
        });
        long durationMs = audio.durationMs() > 0 ? audio.durationMs() : WavUtils.durationMs(audio.wav());
        var label = AiContentLabel.of("Narrivy API", audio.engineMode());
        byte[] out;
        if ("mp3".equals(req.format())) {
            out = AiContentLabel.mp3(engine.toMp3(audio.wav(), accounts.effectiveBitrate()), label);
        } else {
            out = AiContentLabel.wav(audio.wav(), label);
        }
        accounts.recordUsage(uid, "api", req.characters(), durationMs, apiKeyId);
        return new Result(out, "mp3".equals(req.format()) ? "audio/mpeg" : "audio/wav", req.format(), durationMs,
                audio.engineMode(), req.characters());
    }

    private static String limit(String v, int max, String field) {
        if (v == null || v.isBlank()) return null;
        if (v.length() > max) throw new IllegalArgumentException("Pole „" + field + "” może mieć najwyżej " + max + " znaków.");
        return v.trim();
    }
}
