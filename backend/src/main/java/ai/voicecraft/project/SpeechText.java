package ai.voicecraft.project;

import ai.voicecraft.pronunciation.PronunciationEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Przetwarzanie tekstu fragmentu przed syntezą:
 *  - znaczniki pauz: [pauza 1.5s], [pauza 500ms], [pause 2s] — zamieniane na dokładną ciszę,
 *  - słownik wymowy: zamiana całych słów/fraz (np. „VoxCPM” → „woks ce pe em”).
 */
public final class SpeechText {
    public static final Pattern PAUSE = Pattern.compile(
            "\\[\\s*(?:pauza|pause)\\s+(\\d+(?:[.,]\\d+)?)\\s*(ms|s)?\\s*]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final int MIN_PAUSE_MS = 50;
    static final int MAX_PAUSE_MS = 10_000;

    private SpeechText() {}

    /** Część fragmentu: tekst do syntezy albo pauza (ms). */
    public record Part(String text, int pauseMs) {
        public boolean isPause() {
            return text == null;
        }
    }

    public static List<Part> parts(String text) {
        var out = new ArrayList<Part>();
        Matcher m = PAUSE.matcher(text);
        int last = 0;
        while (m.find()) {
            addText(out, text.substring(last, m.start()));
            out.add(new Part(null, pauseMs(m.group(1), m.group(2))));
            last = m.end();
        }
        addText(out, text.substring(last));
        return out;
    }

    static int pauseMs(String value, String unit) {
        double v = Double.parseDouble(value.replace(',', '.'));
        int ms = (int) Math.round("ms".equalsIgnoreCase(unit) ? v : v * 1000);
        return Math.max(MIN_PAUSE_MS, Math.min(MAX_PAUSE_MS, ms));
    }

    private static void addText(List<Part> out, String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        if (!t.isEmpty()) out.add(new Part(t, 0));
    }

    /** Tekst bez znaczników pauz — do liczenia znaków (limity) i napisów. */
    public static String withoutPauses(String text) {
        return PAUSE.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    public static int spokenCharacters(String text) {
        return withoutPauses(text).length();
    }

    /**
     * Stosuje słownik wymowy. Dopasowanie całych słów (granice liter/cyfr); dłuższe hasła mają pierwszeństwo,
     * a znaczniki pauz nie są modyfikowane.
     */
    public static String applyDictionary(String text, List<PronunciationEntry> entries) {
        if (entries == null || entries.isEmpty()) return text;
        var sorted = entries.stream().filter(e -> e.term != null && !e.term.isBlank())
                .sorted(Comparator.comparingInt((PronunciationEntry e) -> e.term.length()).reversed()).toList();
        // Chronimy znaczniki pauz przed zamianą
        var markers = new ArrayList<String>();
        Matcher pm = PAUSE.matcher(text);
        var sb = new StringBuilder();
        while (pm.find()) {
            markers.add(pm.group());
            pm.appendReplacement(sb, Matcher.quoteReplacement("\u0001" + (markers.size() - 1) + "\u0002"));
        }
        pm.appendTail(sb);
        String result = sb.toString();
        for (PronunciationEntry e : sorted) {
            int flags = e.caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(e.term.trim()) + "(?![\\p{L}\\p{N}])", flags);
            result = p.matcher(result).replaceAll(Matcher.quoteReplacement(e.replacement.trim()));
        }
        for (int i = 0; i < markers.size(); i++) result = result.replace("\u0001" + i + "\u0002", markers.get(i));
        return result;
    }

    /** Czy hasło słownika występuje w tekście (do podświetlania w interfejsie). */
    public static boolean contains(String text, PronunciationEntry e) {
        return !applyDictionary(text, List.of(e)).equals(text);
    }

    public static String normalizeTerm(String term) {
        return term == null ? "" : term.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
