package ai.voicecraft.audio;

import jakarta.persistence.*;
import java.time.Instant;

/** Plik audio w magazynie z właścicielem i rozmiarem — podstawa limitu miejsca na nagrania. */
@Entity
@Table(name = "stored_files")
public class StoredFile {
    @Id
    @Column(name = "file_key")
    public String key;

    /** Właściciel (null = plik systemowy, np. próbka głosu z biblioteki — nie liczy się do limitu). */
    public String userId;

    public long bytes;
    public Instant createdAt = Instant.now();

    public static StoredFile of(String key, String userId, long bytes) {
        var f = new StoredFile();
        f.key = key;
        f.userId = userId;
        f.bytes = bytes;
        return f;
    }
}
