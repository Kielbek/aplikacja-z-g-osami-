package ai.voicecraft.history;

import ai.voicecraft.account.AccountService;
import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.common.ApiException;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/history")
public class HistoryController {
    private final HistoryRepository repo;
    private final AudioStorage storage;
    private final AccountService account;

    public HistoryController(HistoryRepository repo, AudioStorage storage, AccountService account) {
        this.repo = repo;
        this.storage = storage;
        this.account = account;
    }

    public record HistoryItem(GenerationRecord record, boolean audioAvailable) {}

    @GetMapping
    public List<HistoryItem> list(@RequestParam(defaultValue = "200") int limit) {
        // Okres przechowywania historii zależy od planu
        var cutoff = account.historyCutoff();
        return repo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(limit, 500))).stream()
                .filter(r -> r.createdAt.isAfter(cutoff))
                .map(r -> new HistoryItem(r, storage.exists(r.audioFile)))
                .toList();
    }

    @GetMapping("/{id}/audio")
    public ResponseEntity<byte[]> audio(@PathVariable Long id) {
        var r = repo.findById(id).orElseThrow(() -> ApiException.notFound("Wpis historii"));
        if (!storage.exists(r.audioFile)) throw ApiException.notFound("Plik audio");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .header("X-Engine-Mode", r.engineMode).body(storage.read(r.audioFile));
    }

    @DeleteMapping
    @Transactional
    public void clear() {
        repo.deleteAll();
    }
}
