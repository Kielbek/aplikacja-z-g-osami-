package ai.voicecraft.publicapi;

import jakarta.persistence.*;
import java.time.Instant;

/** Zadanie syntezy przyjęte przez API i wykonywane w tle (długie teksty, webhook po zakończeniu). */
@Entity
@Table(name = "api_jobs")
public class ApiJob {
    public enum Status { QUEUED, PROCESSING, SUCCEEDED, FAILED;
        public boolean finished() { return this == SUCCEEDED || this == FAILED; }
    }

    @Id
    public String id;

    @Column(nullable = false)
    public String userId;

    public String apiKeyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.QUEUED;

    @Column(nullable = false, columnDefinition = "text")
    public String text;

    @Column(nullable = false)
    public String voiceId;

    @Column(nullable = false)
    public String language;

    @Column(nullable = false)
    public String outputFormat;

    /** Parametry syntezy (JSON {@link SpeechRequest.Settings}). */
    @Column(nullable = false, columnDefinition = "text")
    public String settings;

    public int characters;
    public String idempotencyKey;
    public String callbackUrl;
    public String audioFile;
    public long durationMs;
    public String engineMode;
    public String errorCode;

    @Column(columnDefinition = "text")
    public String errorMessage;

    /** pending | delivered | failed (null = bez webhooka). */
    public String webhookStatus;
    public int webhookAttempts;

    public Instant createdAt = Instant.now();
    public Instant startedAt;
    public Instant finishedAt;
}
