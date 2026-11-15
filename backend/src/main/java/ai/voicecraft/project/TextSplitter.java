package ai.voicecraft.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Podział tekstu na fragmenty (zgodny z podziałem w edytorze). */
public final class TextSplitter {
    /** Docelowa maksymalna długość fragmentu przy automatycznym podziale. */
    public static final int MAX_SEGMENT = 400;
    private static final Pattern SENTENCE = Pattern.compile("[^.!?…]+(?:[.!?…]+[\"”»)]*|$)\\s*");

    private TextSplitter() {}

    public static List<String> paragraphs(String text) {
        return Arrays.stream(text.replace("\r\n", "\n").split("\\n\\s*\\n"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static List<String> sentences(String text) {
        var out = new ArrayList<String>();
        Matcher m = SENTENCE.matcher(text.replaceAll("\\s+", " "));
        while (m.find()) {
            String s = m.group().trim();
            if (!s.isEmpty()) out.add(s);
        }
        if (out.isEmpty() && !text.isBlank()) out.add(text.trim());
        return out;
    }

    /** Akapity; zbyt długie akapity dzielone na grupy zdań nieprzekraczające maxChars. */
    public static List<String> segments(String text, int maxChars) {
        var result = new ArrayList<String>();
        for (String para : paragraphs(text)) {
            if (para.length() <= maxChars) {
                result.add(para);
                continue;
            }
            String current = "";
            for (String sentence : sentences(para)) {
                // Zdanie dłuższe niż limit kolumny — tniemy po słowach
                for (String piece : hardSplit(sentence, 4800)) {
                    if (!current.isEmpty() && (current + " " + piece).length() > maxChars) {
                        result.add(current);
                        current = piece;
                    } else {
                        current = current.isEmpty() ? piece : current + " " + piece;
                    }
                }
            }
            if (!current.isEmpty()) result.add(current);
        }
        return result;
    }

    private static List<String> hardSplit(String s, int max) {
        if (s.length() <= max) return List.of(s);
        var out = new ArrayList<String>();
        var sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (sb.length() + w.length() + 1 > max && sb.length() > 0) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        if (sb.length() > 0) out.add(sb.toString());
        return out;
    }
}
