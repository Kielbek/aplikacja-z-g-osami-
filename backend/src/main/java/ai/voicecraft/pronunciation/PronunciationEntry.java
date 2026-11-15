package ai.voicecraft.pronunciation;

import jakarta.persistence.*;
import java.time.Instant;

/** Wpis słownika wymowy: „term” jest zastępowany przez „replacement” przed syntezą. */
@Entity
@Table(name = "pronunciation_entries")
public class PronunciationEntry {
    @Id
    public String id;

    @Column(nullable = false)
    public String ownerId;

    /** null = wpis globalny użytkownika (wszystkie projekty). */
    public String projectId;

    @Column(nullable = false, length = 200)
    public String term;

    @Column(nullable = false, length = 500)
    public String replacement;

    public boolean caseSensitive;
    public Instant createdAt = Instant.now();
}
