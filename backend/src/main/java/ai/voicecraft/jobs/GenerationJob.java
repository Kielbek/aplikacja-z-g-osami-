package ai.voicecraft.jobs;

import ai.voicecraft.common.StringListConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Zadanie generowania wielu fragmentów — trwałe, więc przeżywa zamknięcie karty i restart serwera. */
@Entity
@Table(name = "generation_jobs")
public class GenerationJob {
    @Id
    public String id;

    @Column(nullable = false)
    public String userId;

    @Column(nullable = false)
    public String projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public JobStatus status = JobStatus.QUEUED;

    @Convert(converter = StringListConverter.class)
    @Column(length = 20000)
    public List<String> segmentIds = new ArrayList<>();

    public int total;
    public int done;
    public int failed;
    public String currentSegmentId;

    @Column(length = 1000)
    public String lastError;

    public String lastErrorCode;
    public boolean cancelRequested;
    public Instant createdAt = Instant.now();
    public Instant startedAt;
    public Instant finishedAt;
}
