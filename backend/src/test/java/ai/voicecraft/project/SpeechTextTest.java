package ai.voicecraft.project;

import static org.assertj.core.api.Assertions.assertThat;

import ai.voicecraft.pronunciation.PronunciationEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpeechTextTest {

    private static PronunciationEntry entry(String term, String replacement, boolean cs) {
        var e = new PronunciationEntry();
        e.term = term;
        e.replacement = replacement;
        e.caseSensitive = cs;
        return e;
    }

    @Test
    void parsesPauseMarkers() {
        var parts = SpeechText.parts("Dzień dobry. [pauza 1.5s] Zaczynamy! [PAUSE 300ms][pauza 2,5 s]Koniec");
        assertThat(parts).extracting(SpeechText.Part::text).containsExactly("Dzień dobry.", null, "Zaczynamy!", null, null, "Koniec");
        assertThat(parts).extracting(SpeechText.Part::pauseMs).containsExactly(0, 1500, 0, 300, 2500, 0);
    }

    @Test
    void clampsPauseLength() {
        assertThat(SpeechText.parts("[pauza 99s]").getFirst().pauseMs()).isEqualTo(10_000);
        assertThat(SpeechText.parts("[pauza 1ms]").getFirst().pauseMs()).isEqualTo(50);
    }

    @Test
    void countsOnlySpokenCharacters() {
        assertThat(SpeechText.spokenCharacters("Ala [pauza 2s] ma kota")).isEqualTo("Ala ma kota".length());
        assertThat(SpeechText.spokenCharacters("[pauza 1s]")).isZero();
    }

    @Test
    void dictionaryReplacesWholeWordsOnly() {
        var dict = List.of(entry("SQL", "es-ku-el", false), entry("API", "a-pe-i", true));
        assertThat(SpeechText.applyDictionary("Baza sql i MySQL, API oraz api.", dict))
                .isEqualTo("Baza es-ku-el i MySQL, a-pe-i oraz api.");
    }

    @Test
    void longerTermsWinAndPauseMarkersAreUntouched() {
        var dict = List.of(entry("Voice", "wojs", false), entry("VoiceCraft AI", "wojskraft aj aj", false), entry("pauza", "przerwa", false));
        assertThat(SpeechText.applyDictionary("VoiceCraft AI [pauza 1s] voice", dict))
                .isEqualTo("wojskraft aj aj [pauza 1s] wojs");
    }

    @Test
    void dictionaryHandlesPolishLetters() {
        var dict = List.of(entry("łódź", "łucz", false));
        assertThat(SpeechText.applyDictionary("Łódź i Łódźka", dict)).isEqualTo("łucz i Łódźka");
    }
}
