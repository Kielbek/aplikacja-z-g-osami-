package ai.voicecraft.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectNamesTest {
    @Test
    void nameComesFromFirstSentenceOfFirstNonEmptySegment() {
        assertThat(ProjectNames.fromTexts(List.of("", "  ", "Rozdział pierwszy. Noc zapadła nad zatoką."))).isEqualTo("Rozdział pierwszy");
        assertThat(ProjectNames.fromTexts(List.of("— witaj w podcaście! Dziś o kawie."))).isEqualTo("Witaj w podcaście");
        assertThat(ProjectNames.fromTexts(List.of("[pauza 1s] Spot radiowy, wersja B"))).isEqualTo("Spot radiowy, wersja B");
        assertThat(ProjectNames.fromTexts(List.of("", "[pauza 2s]"))).isEqualTo(ProjectNames.DEFAULT);
    }

    @Test
    void longSentencesAreCutAtWordBoundary() {
        String name = ProjectNames.fromTexts(List.of("Bardzo długie zdanie bez kropki które ciągnie się i ciągnie przez całą linijkę tekstu"));
        assertThat(name).endsWith("…").hasSizeLessThanOrEqualTo(ProjectNames.MAX + 1);
        assertThat(name).doesNotContain("  ").startsWith("Bardzo długie zdanie");
    }
}
