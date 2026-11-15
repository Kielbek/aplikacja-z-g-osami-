package ai.voicecraft.voice;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;

/** Wygenerowana próbka odsłuchowa głosu w danym języku (pamięć podręczna — opcja A). */
@Entity
@Table(name = "voice_previews")
@IdClass(VoicePreview.Key.class)
public class VoicePreview {
    @Id
    public String voiceId;

    @Id
    public String language;

    @Column(nullable = false)
    public String engineMode;

    @Column(nullable = false)
    public String audioFile;

    public Instant createdAt = Instant.now();

    /** Klucz złożony: głos + język. */
    public static class Key implements Serializable {
        public String voiceId;
        public String language;

        public Key() {}

        public Key(String voiceId, String language) {
            this.voiceId = voiceId;
            this.language = language;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && java.util.Objects.equals(voiceId, k.voiceId) && java.util.Objects.equals(language, k.language);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(voiceId, language);
        }
    }
}
