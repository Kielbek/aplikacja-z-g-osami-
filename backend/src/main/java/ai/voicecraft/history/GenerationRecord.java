package ai.voicecraft.history;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "generation_history")
public class GenerationRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String userId;

    /** segment | voice_design | voice_sample | export */
    public String type;
    public String projectId;
    public String projectName;
    public String segmentId;
    public String voiceId;
    public String voiceName;

    @Column(length = 400)
    public String textPreview;

    public int characters;
    public long durationMs;
    public String engineMode;
    /** success | error */
    public String status;

    @Column(length = 1000)
    public String error;

    public String audioFile;

    /** Odcisk fragmentu z chwili generacji — pozwala przywrócić tę wersję nagrania. */
    @Column(length = 64)
    public String segmentHash;

    @Column(length = 5000)
    public String segmentText;
    public Instant createdAt = Instant.now();
}
