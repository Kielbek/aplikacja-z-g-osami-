package ai.voicecraft.history;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "generation_history")
public class GenerationRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

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
    public Instant createdAt = Instant.now();
}
