package ai.voicecraft.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Edycja jednego fragmentu lub zmiana parametrów nie może wymagać generowania całości. */
class SegmentStatusTest {

    private static Segment generated(String text, VoiceSettings s) {
        var seg = new Segment();
        seg.text = text;
        seg.audioFile = "seg_x.wav";
        seg.engineMode = "voxcpm2";
        seg.generatedHash = GenerationService.hash(text, s);
        return seg;
    }

    @Test
    void readyUntilTextChanges() {
        var s = new VoiceSettings();
        var seg = generated("Dzień dobry.", s);
        assertEquals("ready", ProjectService.segmentStatus(seg, s, "voxcpm2"));
        seg.text = "Dzień dobry!";
        assertEquals("stale", ProjectService.segmentStatus(seg, s, "voxcpm2"));
    }

    @Test
    void staleWhenGenerationParametersChange() {
        var s = new VoiceSettings();
        var seg = generated("Tekst.", s);
        var changed = s.copy();
        changed.voiceId = "voice_warm_narrator";
        assertEquals("stale", ProjectService.segmentStatus(seg, changed, "voxcpm2"));
    }

    @Test
    void demoAudioBecomesStaleWhenRealEngineConnects() {
        var s = new VoiceSettings();
        var seg = generated("Tekst.", s);
        seg.engineMode = "demo";
        assertEquals("stale", ProjectService.segmentStatus(seg, s, "voxcpm2"));
        assertEquals("ready", ProjectService.segmentStatus(seg, s, null)); // silnik offline – nie zmieniaj statusu
    }

    @Test
    void draftAndEmpty() {
        var s = new VoiceSettings();
        var seg = new Segment();
        seg.text = "  ";
        assertEquals("empty", ProjectService.segmentStatus(seg, s, "demo"));
        seg.text = "Nowy";
        assertEquals("draft", ProjectService.segmentStatus(seg, s, "demo"));
    }
}
