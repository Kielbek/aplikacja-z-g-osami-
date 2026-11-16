package ai.voicecraft.audio;

import ai.voicecraft.history.HistoryRepository;
import ai.voicecraft.project.ProjectRepository;
import ai.voicecraft.publicapi.ApiJobRepository;
import ai.voicecraft.voice.VoiceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Jednorazowe uzupełnienie ewidencji miejsca dla plików zapisanych przed jej wprowadzeniem:
 * właściciela ustalamy z odwołań w bazie (fragmenty, historia, własne głosy, zadania API),
 * a rozmiar — z magazynu. Uruchamia się tylko, gdy ewidencja jest pusta.
 */
@Component
public class StorageLedgerBackfill {
    private static final Logger log = LoggerFactory.getLogger(StorageLedgerBackfill.class);

    private final StoredFileRepository files;
    private final AudioStorage storage;
    private final ProjectRepository projects;
    private final HistoryRepository history;
    private final VoiceRepository voices;
    private final ApiJobRepository apiJobs;

    public StorageLedgerBackfill(StoredFileRepository files, AudioStorage storage, ProjectRepository projects,
                                 HistoryRepository history, VoiceRepository voices, ApiJobRepository apiJobs) {
        this.files = files;
        this.storage = storage;
        this.projects = projects;
        this.history = history;
        this.voices = voices;
        this.apiJobs = apiJobs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        if (files.count() > 0) return;
        Map<String, String> owner = new HashMap<>();
        projects.segmentAudioOwners().forEach(r -> owner.putIfAbsent((String) r[0], (String) r[1]));
        history.audioOwners().forEach(r -> owner.putIfAbsent((String) r[0], (String) r[1]));
        apiJobs.audioOwners().forEach(r -> owner.putIfAbsent((String) r[0], (String) r[1]));
        voices.ownedVoiceFiles().forEach(r -> {
            if (r[0] != null) owner.putIfAbsent((String) r[0], (String) r[2]);
            if (r[1] != null) owner.putIfAbsent((String) r[1], (String) r[2]);
        });
        if (owner.isEmpty()) return;
        List<StoredFile> rows = new ArrayList<>();
        try {
            storage.forEachObject(o -> {
                String user = owner.get(o.key());
                if (user != null) rows.add(StoredFile.of(o.key(), user, o.size()));
            });
        } catch (RuntimeException e) {
            log.warn("Nie udało się odczytać magazynu do ewidencji miejsca: {}", e.getMessage());
            return;
        }
        files.saveAll(rows);
        log.info("Ewidencja miejsca: uzupełniono {} plików ({} MB)", rows.size(),
                rows.stream().mapToLong(f -> f.bytes).sum() / 1_000_000);
    }
}
