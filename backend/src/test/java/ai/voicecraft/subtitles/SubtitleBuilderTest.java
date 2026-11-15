package ai.voicecraft.subtitles;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SubtitleBuilderTest {

    @Test
    void timingsFollowSegmentsAndPauses() {
        var cues = SubtitleBuilder.cues(List.of(
                new SubtitleBuilder.SegmentTiming("Pierwsze zdanie. Drugie zdanie.", 4000),
                new SubtitleBuilder.SegmentTiming("Po pauzie [pauza 2s] dalej.", 5000)), 500);
        assertThat(cues).hasSize(4);
        assertThat(cues.get(0).startMs()).isZero();
        assertThat(cues.get(1).endMs()).isEqualTo(4000);
        // Drugi fragment zaczyna się po 4 s + 0,5 s pauzy między fragmentami
        assertThat(cues.get(2).startMs()).isEqualTo(4500);
        // Napis po znaczniku pauzy startuje dokładnie 2 s później niż koniec poprzedniego
        assertThat(cues.get(3).startMs() - cues.get(2).endMs()).isEqualTo(2000);
        assertThat(cues.get(3).endMs()).isEqualTo(9500);
        assertThat(cues).extracting(SubtitleBuilder.Cue::text).doesNotContain("[pauza 2s]");
    }

    @Test
    void longSentencesAreWrappedIntoTwoLines() {
        String s = "To jest bardzo długie zdanie, które musi zostać podzielone na kilka napisów, bo nie mieści się w dwóch liniach tekstu na ekranie.";
        var cues = SubtitleBuilder.cues(List.of(new SubtitleBuilder.SegmentTiming(s, 8000)), 0);
        assertThat(cues.size()).isGreaterThan(1);
        for (var c : cues) {
            for (String line : c.text().split("\n")) assertThat(line.length()).isLessThanOrEqualTo(SubtitleBuilder.CUE);
            assertThat(c.text().split("\n").length).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void formatsSrtAndVtt() {
        var cues = List.of(new SubtitleBuilder.Cue(0, 1500, "Witaj"), new SubtitleBuilder.Cue(3_723_004, 3_725_000, "Koniec"));
        assertThat(SubtitleBuilder.srt(cues)).startsWith("1\n00:00:00,000 --> 00:00:01,500\nWitaj\n\n2\n01:02:03,004 --> ");
        assertThat(SubtitleBuilder.vtt(cues)).startsWith("WEBVTT\n\n00:00:00.000 --> 00:00:01.500\nWitaj");
    }
}
