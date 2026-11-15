package ai.voicecraft.project;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos.*;
import ai.voicecraft.voice.Voice;
import ai.voicecraft.voice.VoiceAnchorService;
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
    private final VoiceAnchorService anchors;

    public GenerationService(EngineClient engine, VoiceAnchorService anchors) {
        this.engine = engine;
        this.anchors = anchors;
    }

    /**
     * Synteza tekstu wskazanym głosem. Gdy silnik obsługuje nagrania referencyjne, głos jest odtwarzany
     * z nagrania wzorcowego (stała barwa we wszystkich fragmentach); w trybie demo — z opisu.
     */
    public Audio synthesize(String text, VoiceSettings s, Voice voice) {
        Health h = engine.requireOnline();
        if (voice == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Wybierz głos przed generowaniem.");
        if (voice.kind == ai.voicecraft.voice.VoiceKind.CLONED && !anchors.supported(h) && !"demo".equals(h.mode())) {
            throw new ApiException(HttpStatus.CONFLICT, "Głos sklonowany „" + voice.name
                    + "” wymaga modelu VoxCPM2. Bieżący silnik (" + h.mode() + ") ma tylko gotowe głosy — wybierz głos z biblioteki.");
        }
        String ref = anchors.ensure(voice, h);
        try {
            return engine.synthesize(request(text, s, voice, ref));
        } catch (ApiException e) {
            if (ref == null || !VoiceAnchorService.isMissingReference(e)) throw e;
            anchors.recover(voice); // silnik utracił referencję (np. nowy kontener) — zarejestruj ponownie
            return engine.synthesize(request(text, s, voice, voice.referenceId));
        }
    }

    private static SynthesizeRequest request(String text, VoiceSettings s, Voice voice, String referenceId) {
        // Z nagraniem wzorcowym opis barwy jest zbędny (a nawet szkodliwy) — barwę niesie referencja
        var spec = new VoiceSpec(voice.id, referenceId == null ? voice.enginePrompt : null, referenceId, voice.gender, voice.age);
        return new SynthesizeRequest(text, s.language, spec, params(s));
    }

    /**
     * Synteza fragmentu z pauzami: każda część tekstu jest syntezowana osobno, a w miejscu znaczników
     * [pauza …] wstawiana jest cisza o dokładnej długości. Tekst bez znaczników = jedno wywołanie silnika.
     */
    public Audio synthesizeSegment(String effectiveText, VoiceSettings s, Voice voice) {
        var parts = SpeechText.parts(effectiveText);
        if (parts.stream().noneMatch(p -> !p.isPause())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Fragment zawiera tylko pauzy — dodaj tekst.");
        }
        if (parts.size() == 1) return synthesize(parts.getFirst().text(), s, voice);
        var pieces = new java.util.ArrayList<Object>();
        String mode = null;
        for (var part : parts) {
            if (part.isPause()) {
                pieces.add(part.pauseMs());
            } else {
                Audio a = synthesize(part.text(), s, voice);
                mode = a.engineMode();
                pieces.add(a.wav());
            }
        }
        byte[] wav = ai.voicecraft.audio.WavUtils.joinParts(pieces);
        return new Audio(wav, mode, ai.voicecraft.audio.WavUtils.durationMs(wav));
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
        return hash(text, s, null);
    }

    /**
     * Odcisk fragmentu: tekst po zastosowaniu słownika, parametry projektu i (opcjonalnie) głos fragmentu.
     * Bez głosu fragmentu wynik jest identyczny jak w poprzednich wersjach (zachowana zgodność statusów).
     */
    public static String hash(String effectiveText, VoiceSettings s, String segmentVoiceId) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(effectiveText.trim().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(s.fingerprint().getBytes(StandardCharsets.UTF_8));
            if (segmentVoiceId != null) md.update(("\u0000voice:" + segmentVoiceId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
