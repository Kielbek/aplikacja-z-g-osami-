package ai.voicecraft.subtitles;

import ai.voicecraft.project.SpeechText;
import ai.voicecraft.project.TextSplitter;
import java.util.ArrayList;
import java.util.List;

/**
 * Napisy SRT / WebVTT z czasami fragmentów. W obrębie fragmentu pauzy ze znaczników mają dokładną długość,
 * a pozostały czas jest dzielony proporcjonalnie do liczby znaków. Napis: maks. 2 linie po 42 znaki.
 */
public final class SubtitleBuilder {
    static final int LINE = 42;
    static final int CUE = LINE * 2;

    private SubtitleBuilder() {}

    public record SegmentTiming(String text, long durationMs) {}

    public record Cue(long startMs, long endMs, String text) {}

    public static List<Cue> cues(List<SegmentTiming> segments, long pauseBetweenMs) {
        var out = new ArrayList<Cue>();
        long offset = 0;
        for (SegmentTiming seg : segments) {
            var parts = SpeechText.parts(seg.text());
            long pauses = parts.stream().filter(SpeechText.Part::isPause).mapToLong(SpeechText.Part::pauseMs).sum();
            int chars = parts.stream().filter(p -> !p.isPause()).mapToInt(p -> p.text().length()).sum();
            double speechMs = Math.max(0, seg.durationMs() - pauses);
            double t = offset;
            for (var part : parts) {
                if (part.isPause()) {
                    t += part.pauseMs();
                    continue;
                }
                double partMs = chars == 0 ? 0 : speechMs * part.text().length() / chars;
                List<String> chunks = chunks(part.text());
                int partChars = chunks.stream().mapToInt(String::length).sum();
                for (String chunk : chunks) {
                    double ms = partChars == 0 ? 0 : partMs * chunk.length() / partChars;
                    out.add(new Cue(Math.round(t), Math.round(t + ms), wrap(chunk)));
                    t += ms;
                }
            }
            offset += seg.durationMs() + pauseBetweenMs;
        }
        return out;
    }

    /** Zdania, a zbyt długie zdania — po słowach, do CUE znaków. */
    static List<String> chunks(String text) {
        var out = new ArrayList<String>();
        for (String sentence : TextSplitter.sentences(text)) {
            if (sentence.length() <= CUE) {
                out.add(sentence);
                continue;
            }
            var sb = new StringBuilder();
            for (String w : sentence.split(" ")) {
                if (sb.length() > 0 && sb.length() + 1 + w.length() > CUE) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                if (sb.length() > 0) sb.append(' ');
                sb.append(w);
            }
            if (sb.length() > 0) out.add(sb.toString());
        }
        return out;
    }

    /** Podział na dwie linie w miejscu spacji najbliższej środka. */
    static String wrap(String s) {
        if (s.length() <= LINE) return s;
        int mid = s.length() / 2;
        int best = -1;
        for (int d = 0; d < mid; d++) {
            if (mid - d > 0 && s.charAt(mid - d) == ' ') { best = mid - d; break; }
            if (mid + d < s.length() && s.charAt(mid + d) == ' ') { best = mid + d; break; }
        }
        return best < 0 ? s : s.substring(0, best) + "\n" + s.substring(best + 1);
    }

    public static String srt(List<Cue> cues) {
        var sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            sb.append(i + 1).append('\n')
                    .append(time(c.startMs(), ',')).append(" --> ").append(time(c.endMs(), ',')).append('\n')
                    .append(c.text()).append("\n\n");
        }
        return sb.toString();
    }

    public static String vtt(List<Cue> cues) {
        var sb = new StringBuilder("WEBVTT\n\n");
        for (Cue c : cues) {
            sb.append(time(c.startMs(), '.')).append(" --> ").append(time(c.endMs(), '.')).append('\n')
                    .append(c.text()).append("\n\n");
        }
        return sb.toString();
    }

    static String time(long ms, char sep) {
        long h = ms / 3_600_000, m = ms / 60_000 % 60, s = ms / 1000 % 60, milli = ms % 1000;
        return String.format("%02d:%02d:%02d%c%03d", h, m, s, sep, milli);
    }
}
