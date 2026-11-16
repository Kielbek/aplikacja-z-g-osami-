package ai.voicecraft.jobs;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.project.ProjectDtos.ProjectDto;
import ai.voicecraft.project.ProjectDtos.SegmentDto;
import ai.voicecraft.project.ProjectService;
import ai.voicecraft.security.CurrentUser;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Kolejka generowania. Fragmenty są generowane po kolei w tle; postęp trafia do bazy i przez SSE
 * do przeglądarki. Zamknięcie karty nie przerywa pracy, a po restarcie serwera zadania są wznawiane.
 */
@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final Set<JobStatus> ACTIVE = EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING);
    private static final Set<String> PENDING = Set.of("draft", "stale", "error");

    private final JobRepository jobs;
    private final ProjectService projects;
    private final EventHub events;
    private final ThreadPoolTaskExecutor executor;
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    public JobService(JobRepository jobs, ProjectService projects, EventHub events,
                      @Value("${voicecraft.jobs.concurrency:2}") int concurrency) {
        this.jobs = jobs;
        this.projects = projects;
        this.events = events;
        this.executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("gen-");
        executor.initialize();
    }

    public record JobDto(String id, String projectId, JobStatus status, int total, int done, int failed,
                         String currentSegmentId, String lastError, String lastErrorCode, Instant createdAt) {
        static JobDto of(GenerationJob j) {
            return new JobDto(j.id, j.projectId, j.status, j.total, j.done, j.failed, j.currentSegmentId, j.lastError,
                    j.lastErrorCode, j.createdAt);
        }
    }

    /** Uruchamia generowanie. Bez listy — wszystkie nowe, zmienione i błędne fragmenty. */
    public JobDto start(String projectId, List<String> segmentIds) {
        String uid = CurrentUser.id();
        ProjectDto project = projects.get(projectId); // weryfikuje właściciela
        var existing = jobs.findFirstByProjectIdAndUserIdAndStatusInOrderByCreatedAtDesc(projectId, uid, ACTIVE);
        if (existing.isPresent()) return JobDto.of(existing.get());

        List<String> ids = project.segments().stream()
                .filter(s -> segmentIds == null ? PENDING.contains(s.status()) : segmentIds.contains(s.id()) && !"empty".equals(s.status()))
                .map(SegmentDto::id)
                .toList();
        if (ids.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Brak fragmentów do wygenerowania.");

        var job = new GenerationJob();
        job.id = UUID.randomUUID().toString();
        job.userId = uid;
        job.projectId = projectId;
        job.segmentIds = ids;
        job.total = ids.size();
        jobs.save(job);
        events.publish(uid, projectId, "job", JobDto.of(job));
        executor.execute(() -> run(job.id));
        return JobDto.of(job);
    }

    public JobDto active(String projectId) {
        projects.get(projectId);
        return jobs.findFirstByProjectIdAndUserIdAndStatusInOrderByCreatedAtDesc(projectId, CurrentUser.id(), ACTIVE)
                .map(JobDto::of).orElse(null);
    }

    public JobDto cancel(String jobId) {
        var job = jobs.findByIdAndUserId(jobId, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Zadanie"));
        if (job.status.active()) {
            job.cancelRequested = true;
            cancelFlags.put(job.id, true);
            if (job.status == JobStatus.QUEUED) finish(job, JobStatus.CANCELLED);
            else jobs.save(job);
        }
        return JobDto.of(job);
    }

    /** Wznawia zadania przerwane restartem serwera (od miejsca, w którym się zatrzymały). */
    @EventListener(ApplicationReadyEvent.class)
    void resumeInterrupted() {
        for (GenerationJob job : jobs.findAllByStatusIn(ACTIVE)) {
            log.info("Wznawiam zadanie generowania {} ({}/{})", job.id, job.done, job.total);
            executor.execute(() -> run(job.id));
        }
    }

    void run(String jobId) {
        GenerationJob job = jobs.findById(jobId).orElse(null);
        if (job == null || !job.status.active()) return;
        job.status = JobStatus.RUNNING;
        if (job.startedAt == null) job.startedAt = Instant.now();
        jobs.save(job);
        publish(job);
        try {
            for (int i = job.done; i < job.segmentIds.size(); i++) {
                if (cancelRequested(job)) {
                    finish(job, JobStatus.CANCELLED);
                    return;
                }
                String segmentId = job.segmentIds.get(i);
                job.currentSegmentId = segmentId;
                jobs.save(job);
                publish(job);
                try {
                    // Generowanie całego projektu ustępuje miejsca na GPU żądaniom interaktywnym i API
                    SegmentDto seg = ai.voicecraft.engine.GpuQueue.withPriority(ai.voicecraft.engine.GpuQueue.Priority.BATCH,
                            () -> CurrentUser.runAs(job.userId, () -> projects.generateSegment(job.projectId, segmentId)));
                    events.publish(job.userId, job.projectId, "segment", seg);
                } catch (ApiException e) {
                    job.failed++;
                    job.lastError = e.getMessage();
                    job.lastErrorCode = e.code();
                    events.publish(job.userId, job.projectId, "segment-error", Map.of("segmentId", segmentId, "message", e.getMessage()));
                    if (fatal(e)) {
                        job.done = i + 1;
                        finish(job, JobStatus.FAILED);
                        return;
                    }
                }
                job.done = i + 1;
                jobs.save(job);
            }
            finish(job, job.failed > 0 && job.failed == job.total ? JobStatus.FAILED : JobStatus.DONE);
        } catch (RuntimeException e) {
            log.error("Zadanie {} przerwane błędem", job.id, e);
            job.lastError = "Nieoczekiwany błąd serwera podczas generowania.";
            finish(job, JobStatus.FAILED);
        }
    }

    /** Błędy, przy których dalsze generowanie nie ma sensu (limit planu, silnik niedostępny, projekt usunięty). */
    private static boolean fatal(ApiException e) {
        HttpStatus s = e.status();
        return "EMAIL_NOT_VERIFIED".equals(e.code()) || s == HttpStatus.PAYMENT_REQUIRED || s == HttpStatus.SERVICE_UNAVAILABLE || s == HttpStatus.TOO_MANY_REQUESTS
                || (s == HttpStatus.NOT_FOUND && e.getMessage() != null && e.getMessage().startsWith("Projekt"));
    }

    private boolean cancelRequested(GenerationJob job) {
        if (Boolean.TRUE.equals(cancelFlags.get(job.id))) return true;
        return jobs.findById(job.id).map(j -> j.cancelRequested).orElse(true);
    }

    private void finish(GenerationJob job, JobStatus status) {
        job.status = status;
        job.currentSegmentId = null;
        job.finishedAt = Instant.now();
        jobs.save(job);
        cancelFlags.remove(job.id);
        publish(job);
    }

    private void publish(GenerationJob job) {
        events.publish(job.userId, job.projectId, "job", JobDto.of(job));
    }
}
