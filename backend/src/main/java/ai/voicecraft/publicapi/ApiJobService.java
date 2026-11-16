package ai.voicecraft.publicapi;

import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.engine.GpuQueue;
import ai.voicecraft.security.CurrentUser;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Zadania syntezy w tle: przyjęcie (z kluczem idempotencji), wykonanie w kolejce GPU z niskim priorytetem,
 * przechowanie wyniku przez {@link #RETENTION} i powiadomienie webhookiem z ponowieniami.
 */
@Service
public class ApiJobService {
    private static final Logger log = LoggerFactory.getLogger(ApiJobService.class);
    public static final Duration RETENTION = Duration.ofDays(7);
    static final int MAX_PENDING_PER_USER = 100;
    /** Odstępy kolejnych prób dostarczenia webhooka. */
    static final long[] WEBHOOK_BACKOFF_SECONDS = {0, 10, 60, 300, 1800};
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApiJobRepository jobs;
    private final SpeechService speech;
    private final AudioStorage storage;
    private final WebhookSender webhooks;
    private final ApiKeyService keys;
    private final ai.voicecraft.account.AccountService accounts;
    private final ScheduledExecutorService workers;
    private final ScheduledExecutorService delivery = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "api-webhooks");
        t.setDaemon(true);
        return t;
    });

    public ApiJobService(ApiJobRepository jobs, SpeechService speech, AudioStorage storage, WebhookSender webhooks,
                         ApiKeyService keys, ai.voicecraft.account.AccountService accounts,
                         @Value("${voicecraft.api.job-workers:2}") int workers) {
        this.accounts = accounts;
        this.jobs = jobs;
        this.speech = speech;
        this.storage = storage;
        this.webhooks = webhooks;
        this.keys = keys;
        this.workers = Executors.newScheduledThreadPool(Math.max(1, workers), r -> {
            var t = new Thread(r, "api-jobs");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        delivery.shutdownNow();
    }

    /** Przyjmuje zadanie. Ten sam {@code Idempotency-Key} zwraca istniejące zadanie zamiast tworzyć nowe. */
    public ApiJob create(SpeechRequest r, String apiKeyId, String idempotencyKey, int maxCharacters) {
        String uid = CurrentUser.id();
        if (idempotencyKey != null) {
            if (idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
                throw new IllegalArgumentException("Nagłówek Idempotency-Key musi mieć od 1 do 255 znaków.");
            }
            var existing = jobs.findByUserIdAndIdempotencyKey(uid, idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }
        webhooks.validate(r.callbackUrl());
        var prepared = speech.prepare(r.text(), r.voiceId(), r.language(), r.format(), r.settings(), maxCharacters);
        // Cały tekst zadania musi zmieścić się w dostępnych znakach już przy przyjęciu (rezerwacja do końca zadania)
        accounts.requireGenerationQuota(prepared.characters());
        if (jobs.countByUserIdAndStatusIn(uid, EnumSet.of(ApiJob.Status.QUEUED, ApiJob.Status.PROCESSING)) >= MAX_PENDING_PER_USER) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Masz już " + MAX_PENDING_PER_USER
                    + " zadań w kolejce. Poczekaj na ich zakończenie.", "too_many_pending_jobs");
        }
        var j = new ApiJob();
        j.id = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        j.userId = uid;
        j.apiKeyId = apiKeyId;
        j.text = prepared.text();
        j.voiceId = prepared.voice().id;
        j.language = prepared.language();
        j.outputFormat = prepared.format();
        j.settings = JSON.writeValueAsString(r.settings());
        j.characters = prepared.characters();
        j.idempotencyKey = idempotencyKey;
        j.callbackUrl = r.callbackUrl() == null || r.callbackUrl().isBlank() ? null : r.callbackUrl().trim();
        j.webhookStatus = j.callbackUrl == null ? null : "pending";
        try {
            jobs.saveAndFlush(j);
        } catch (DataIntegrityViolationException e) {
            // Równoległe żądanie z tym samym kluczem idempotencji zdążyło pierwsze
            return jobs.findByUserIdAndIdempotencyKey(uid, idempotencyKey).orElseThrow(() -> e);
        }
        workers.execute(() -> run(j.id));
        return j;
    }

    public ApiJob get(String id) {
        return jobs.findByIdAndUserId(id, CurrentUser.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Zadanie „" + id + "” nie istnieje lub wygasło.", "job_not_found"));
    }

    public byte[] audio(ApiJob j) {
        if (j.status != ApiJob.Status.SUCCEEDED || j.audioFile == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Nagranie nie jest jeszcze gotowe (status: " + status(j) + ").", "job_not_ready");
        }
        return storage.read(j.audioFile);
    }

    /** Po restarcie: zadania przerwane w trakcie wracają do kolejki, a niedostarczone webhooki — do wysyłki. */
    @EventListener(ApplicationReadyEvent.class)
    void resume() {
        for (ApiJob j : jobs.findAllByStatusInOrderByCreatedAtAsc(EnumSet.of(ApiJob.Status.QUEUED, ApiJob.Status.PROCESSING))) {
            j.status = ApiJob.Status.QUEUED;
            jobs.save(j);
            workers.execute(() -> run(j.id));
        }
    }

    void run(String jobId) {
        ApiJob j = jobs.findById(jobId).orElse(null);
        if (j == null || j.status.finished()) return;
        j.status = ApiJob.Status.PROCESSING;
        j.startedAt = Instant.now();
        jobs.save(j);
        try {
            var s = JSON.readValue(j.settings, SpeechRequest.Settings.class);
            SpeechService.Result r = CurrentUser.runAs(j.userId, () -> {
                var prepared = speech.prepare(j.text, j.voiceId, j.language, j.outputFormat, s, Integer.MAX_VALUE);
                return ai.voicecraft.account.AccountService.withinReservation("api:" + j.id,
                        () -> speech.render(prepared, j.apiKeyId, GpuQueue.Priority.BATCH));
            });
            j.audioFile = storage.save("api", r.audio());
            j.durationMs = r.durationMs();
            j.engineMode = r.engineMode();
            j.status = ApiJob.Status.SUCCEEDED;
        } catch (ApiException e) {
            j.status = ApiJob.Status.FAILED;
            j.errorCode = ApiErrors.code(e);
            j.errorMessage = e.getMessage();
        } catch (IllegalArgumentException e) {
            j.status = ApiJob.Status.FAILED;
            j.errorCode = "invalid_request";
            j.errorMessage = e.getMessage();
        } catch (RuntimeException e) {
            log.error("Zadanie API {} nie powiodło się", j.id, e);
            j.status = ApiJob.Status.FAILED;
            j.errorCode = "internal_error";
            j.errorMessage = "Wystąpił nieoczekiwany błąd podczas generowania.";
        }
        j.finishedAt = Instant.now();
        jobs.save(j);
        if (j.callbackUrl != null) scheduleWebhook(j.id, 0);
    }

    private void scheduleWebhook(String jobId, int attempt) {
        delivery.schedule(() -> deliver(jobId, attempt), WEBHOOK_BACKOFF_SECONDS[attempt], TimeUnit.SECONDS);
    }

    void deliver(String jobId, int attempt) {
        ApiJob j = jobs.findById(jobId).orElse(null);
        if (j == null || j.callbackUrl == null) return;
        String event = j.status == ApiJob.Status.SUCCEEDED ? "job.succeeded" : "job.failed";
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", "evt_" + j.id.substring(4) + "_" + attempt);
        payload.put("type", event);
        payload.put("created_at", Instant.now().toString());
        payload.put("data", dto(j));
        int code = webhooks.send(j.callbackUrl, keys.webhookSecretOf(j.userId), JSON.writeValueAsString(payload));
        j.webhookAttempts = attempt + 1;
        if (code >= 200 && code < 300) {
            j.webhookStatus = "delivered";
        } else if (attempt + 1 < WEBHOOK_BACKOFF_SECONDS.length) {
            j.webhookStatus = "pending";
            scheduleWebhook(jobId, attempt + 1);
        } else {
            j.webhookStatus = "failed";
            log.info("Webhook zadania {} niedostarczony po {} próbach (ostatnia odpowiedź: {})", j.id, j.webhookAttempts, code);
        }
        jobs.save(j);
    }

    /** Usuwa zadania i nagrania starsze niż {@link #RETENTION} (wywoływane przez nocne sprzątanie). */
    public int deleteExpired(Instant now) {
        List<ApiJob> old = jobs.findCreatedBefore(now.minus(RETENTION));
        for (ApiJob j : old) {
            if (j.audioFile != null) {
                try {
                    storage.delete(j.audioFile);
                } catch (RuntimeException ignored) {
                    // plik mógł już zostać usunięty
                }
            }
        }
        jobs.deleteAll(old);
        return old.size();
    }

    static String status(ApiJob j) {
        return j.status.name().toLowerCase();
    }

    /** Reprezentacja zadania w API (snake_case). */
    static Map<String, Object> dto(ApiJob j) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", j.id);
        m.put("object", "text_to_speech_job");
        m.put("status", status(j));
        m.put("voice_id", j.voiceId);
        m.put("language", j.language);
        m.put("format", j.outputFormat);
        m.put("characters", j.characters);
        m.put("created_at", j.createdAt.toString());
        m.put("started_at", j.startedAt == null ? null : j.startedAt.toString());
        m.put("finished_at", j.finishedAt == null ? null : j.finishedAt.toString());
        m.put("expires_at", j.createdAt.plus(RETENTION).toString());
        if (j.status == ApiJob.Status.SUCCEEDED) {
            m.put("duration_ms", j.durationMs);
            m.put("audio_url", "/v1/text-to-speech/jobs/" + j.id + "/audio");
            m.put("demo", "demo".equals(j.engineMode));
        }
        if (j.status == ApiJob.Status.FAILED) m.put("error", Map.of("code", j.errorCode, "message", j.errorMessage == null ? "" : j.errorMessage));
        if (j.callbackUrl != null) m.put("webhook", Map.of("url", j.callbackUrl, "status", j.webhookStatus, "attempts", j.webhookAttempts));
        return m;
    }
}
