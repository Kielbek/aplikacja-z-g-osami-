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
    /** Część znaków pokryta pakietem dodatkowych znaków (nie liczy się do limitu planu). */
    public int creditCharacters;
    public long durationMs;
    public Instant createdAt = Instant.now();

    public static UsageEvent of(String userId, String kind, int characters, long durationMs) {
        return of(userId, kind, characters, 0, durationMs);
    }

    public static UsageEvent of(String userId, String kind, int characters, int creditCharacters, long durationMs) {
        var e = new UsageEvent();
        e.userId = userId;
        e.kind = kind;
        e.characters = characters;
        e.creditCharacters = creditCharacters;
        e.durationMs = durationMs;
        return e;
    }
}
