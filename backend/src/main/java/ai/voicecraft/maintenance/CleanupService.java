package ai.voicecraft.maintenance;

import ai.voicecraft.account.SubscriptionPlan;
import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.history.HistoryRepository;
import ai.voicecraft.project.ProjectRepository;
import ai.voicecraft.user.EmailVerificationTokenRepository;
import ai.voicecraft.user.PasswordResetTokenRepository;
import ai.voicecraft.voice.VoiceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nocne sprzątanie danych:
 * <ol>
 *   <li>historia generowania starsza niż okres przechowywania planu właściciela,</li>
 *   <li>pliki audio, do których nic już nie prowadzi (stare wersje fragmentów, usunięte projekty i głosy,
 *       niezapisane podglądy projektowania) — z zapasem 24 h, aby nie usunąć pliku zapisywanego właśnie teraz,</li>
 *   <li>wygasłe tokeny resetu hasła i potwierdzenia e-mail,</li>
 *   <li>puste projekty (bez tekstu, nigdy niegenerowane) nieruszane od doby.</li>
 * </ol>
 * Zużycie limitów (usage_events) nie jest usuwane — nie zależy od historii.
 */
@Service
public class CleanupService {
    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);
    static final Duration ORPHAN_GRACE = Duration.ofHours(24);

    private final HistoryRepository history;
    private final ProjectRepository projects;
    private final VoiceRepository voices;
    private final AudioStorage storage;
    private final PasswordResetTokenRepository resetTokens;
    private final EmailVerificationTokenRepository verificationTokens;
    private final ai.voicecraft.voice.VoicePreviewRepository previews;
    private final ai.voicecraft.publicapi.ApiJobService apiJobs;
    private final ai.voicecraft.publicapi.ApiJobRepository apiJobRepo;
    private final ai.voicecraft.audio.StoredFileRepository storedFiles;

    public CleanupService(HistoryRepository history, ProjectRepository projects, VoiceRepository voices, AudioStorage storage,
                          PasswordResetTokenRepository resetTokens, EmailVerificationTokenRepository verificationTokens,
                          ai.voicecraft.voice.VoicePreviewRepository previews,
                          ai.voicecraft.publicapi.ApiJobService apiJobs, ai.voicecraft.publicapi.ApiJobRepository apiJobRepo,
                          ai.voicecraft.audio.StoredFileRepository storedFiles) {
        this.storedFiles = storedFiles;
        this.apiJobs = apiJobs;
        this.apiJobRepo = apiJobRepo;
        this.previews = previews;
        this.history = history;
        this.projects = projects;
        this.voices = voices;
        this.storage = storage;
        this.resetTokens = resetTokens;
        this.verificationTokens = verificationTokens;
    }

    public record Report(int historyRemoved, long filesScanned, long filesRemoved, long bytesFreed, int tokensRemoved,
                         int emptyProjectsRemoved) {}

    @Scheduled(cron = "${voicecraft.cleanup.cron:0 30 3 * * *}")
    public void nightly() {
        try {
            Report r = run(Instant.now());
            log.info("Sprzątanie: usunięto {} wpisów historii, {} z {} plików ({} MB), {} tokenów, {} pustych projektów",
                    r.historyRemoved(), r.filesRemoved(), r.filesScanned(), r.bytesFreed() / 1_048_576, r.tokensRemoved(),
                    r.emptyProjectsRemoved());
        } catch (RuntimeException e) {
            log.error("Sprzątanie danych nie powiodło się", e);
        }
    }

    public Report run(Instant now) {
        int removed = 0;
        for (SubscriptionPlan plan : SubscriptionPlan.values()) {
            removed += history.deleteExpired(plan, now.minus(Duration.ofDays(plan.historyRetentionDays)));
            // Plan Free: nagrania fragmentów przechowujemy rok od wygenerowania (tekst projektu zostaje)
            if (plan.audioRetentionDays > 0) projects.expireAudio(plan, now.minus(Duration.ofDays(plan.audioRetentionDays)));
        }
        int tokens = resetTokens.deleteExpired(now) + verificationTokens.deleteExpired(now);
        // Puste projekty (np. „Nowy projekt” zamknięty bez wpisania tekstu) — po dobie bez zmian
        var empty = projects.findEmptyBefore(now.minus(Duration.ofDays(1)));
        projects.deleteAll(empty);

        // Zbiór referencji budowany PO usunięciu historii — pliki wygasłych wersji stają się osierocone
        // Wyniki zadań API są dostępne przez 7 dni
        apiJobs.deleteExpired(now);
        Set<String> referenced = new HashSet<>();
        referenced.addAll(apiJobRepo.allAudioFiles());
        referenced.addAll(projects.allSegmentAudioFiles());
        referenced.addAll(voices.allSampleFiles());
        referenced.addAll(voices.allReferenceFiles());
        referenced.addAll(history.allAudioFiles());
        referenced.addAll(previews.allAudioFiles());

        Instant graceLimit = now.minus(ORPHAN_GRACE);
        var scanned = new AtomicLong();
        var deleted = new AtomicLong();
        var bytes = new AtomicLong();
        var firstError = new AtomicReference<RuntimeException>();
        Set<String> existing = new HashSet<>();
        storage.forEachObject(o -> {
            scanned.incrementAndGet();
            existing.add(o.key());
            if (referenced.contains(o.key()) || o.lastModified().isAfter(graceLimit)) return;
            try {
                storage.delete(o.key());
                deleted.incrementAndGet();
                bytes.addAndGet(o.size());
            } catch (RuntimeException e) {
                firstError.compareAndSet(null, e);
            }
        });
        if (firstError.get() != null) log.warn("Nie udało się usunąć części plików: {}", firstError.get().getMessage());
        // Ewidencja miejsca: wpisy plików, których już nie ma w magazynie (np. usuniętych ręcznie)
        storedFiles.findAll().stream().filter(f -> !existing.contains(f.key)).forEach(f -> storedFiles.deleteByKey(f.key));
        return new Report(removed, scanned.get(), deleted.get(), bytes.get(), tokens, empty.size());
    }
}
