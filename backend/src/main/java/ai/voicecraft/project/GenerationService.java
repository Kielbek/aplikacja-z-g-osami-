package ai.voicecraft.project;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos.*;
import ai.voicecraft.voice.Voice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Tłumaczy ustawienia projektu i głos na żądanie do silnika TTS. */
@Service
public class GenerationService {
    private final EngineClient engine;

    public GenerationService(EngineClient engine) {
        this.engine = engine;
    }

    public Audio synthesize(String text, VoiceSettings s, Voice voice) {
        engine.requireOnline();
        if (voice == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Wybierz głos przed generowaniem.");
        var spec = new VoiceSpec(voice.id, voice.enginePrompt, voice.referenceId, voice.gender, voice.age);
        return engine.synthesize(new SynthesizeRequest(text, s.language, spec, params(s)));
    }

    public Audio design(String description, String text, String language, VoiceSettings s) {
        engine.requireOnline();
        return engine.design(new VoiceDesignRequest(description, text, language, params(s)));
    }

    /** Aktualny tryb silnika (voxcpm2/demo) lub null, gdy jest offline. */
    public String currentMode() {
        Health h = engine.health(false);
        return h == null ? null : h.mode();
    }

    public static Params params(VoiceSettings s) {
        return new Params(blank(s.style), blank(s.emotion), s.speed, s.expressiveness, s.cfgValue,
                s.inferenceTimesteps, s.normalize, s.denoise, s.seed);
    }

    public static String hash(String text, VoiceSettings s) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(text.trim().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(s.fingerprint().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
