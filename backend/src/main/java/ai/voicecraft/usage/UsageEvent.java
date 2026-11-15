package ai.voicecraft.usage;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Zdarzenie zużycia limitu (synteza fragmentu, podgląd projektu głosu). Niezależne od historii —
 * użytkownik może wyczyścić historię, ale nie zeruje tym licznika znaków i minut.
 */
@Entity
@Table(name = "usage_events")
public class UsageEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String userId;

    /** segment | voice_design */
    @Column(nullable = false)
    public String kind;

    public int characters;
    public long durationMs;
    public Instant createdAt = Instant.now();

    public static UsageEvent of(String userId, String kind, int characters, long durationMs) {
        var e = new UsageEvent();
        e.userId = userId;
        e.kind = kind;
        e.characters = characters;
        e.durationMs = durationMs;
        return e;
    }
}
