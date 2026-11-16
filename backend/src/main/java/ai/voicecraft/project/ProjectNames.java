package ai.voicecraft.project;

import java.util.List;
import java.util.regex.Pattern;

/** Automatyczna nazwa projektu z początku tekstu — dopóki użytkownik nie nada własnej. */
public final class ProjectNames {
    private ProjectNames() {}

    public static final String DEFAULT = "Projekt bez tytułu";
    static final int MAX = 48;
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?…\\n]");
    private static final Pattern LEADING = Pattern.compile("^[\\s\\-–—„\"'«»(\\[]+");
    private static final Pattern TRAILING = Pattern.compile("[\\s,;:\\-–—„\"'«»)]+$");

    /** Nazwa z pierwszego niepustego fragmentu: pierwsze zdanie, najwyżej ~48 znaków, ucięte na granicy słowa. */
    public static String fromTexts(List<String> texts) {
        for (String t : texts) {
            if (t == null) continue;
            String plain = SpeechText.withoutPauses(t).strip();
            if (plain.isEmpty()) continue;
            var m = SENTENCE_END.matcher(plain);
            String first = m.find() && m.start() > 0 ? plain.substring(0, m.start()) : plain;
            first = LEADING.matcher(first.replaceAll("\\s+", " ")).replaceAll("");
            first = TRAILING.matcher(first).replaceAll("");
            if (first.isEmpty()) continue;
            if (first.length() > MAX) {
                int cut = first.lastIndexOf(' ', MAX);
                first = TRAILING.matcher(first.substring(0, cut > MAX / 2 ? cut : MAX)).replaceAll("") + "…";
            }
            return Character.toUpperCase(first.charAt(0)) + first.substring(1);
        }
        return DEFAULT;
    }
}
