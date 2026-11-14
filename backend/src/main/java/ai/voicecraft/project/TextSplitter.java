package ai.voicecraft.project;

import java.util.Arrays;
import java.util.List;

public final class TextSplitter {
    private TextSplitter() {}

    public static List<String> paragraphs(String text) {
        return Arrays.stream(text.replace("\r\n", "\n").split("\\n\\s*\\n"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
