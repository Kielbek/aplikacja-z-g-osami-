package ai.voicecraft.publicapi;

import jakarta.persistence.*;
import java.time.Instant;

/** Klucz publicznego API. W bazie jest tylko skrót SHA-256 — pełny klucz widać jeden raz, przy utworzeniu. */
@Entity
@Table(name = "api_keys")
public class ApiKey {
    @Id
    public String id;

    @Column(nullable = false)
    public String userId;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public String prefix;

    @Column(nullable = false, unique = true)
    public String keyHash;

    public Instant createdAt = Instant.now();
    public Instant lastUsedAt;
    public Instant revokedAt;

    public boolean active() {
        return revokedAt == null;
    }
}
