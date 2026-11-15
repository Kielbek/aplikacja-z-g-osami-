package ai.voicecraft.history;

import ai.voicecraft.account.AccountService;
import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.security.CurrentUser;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    /** Historia bieżącego użytkownika z okresu przechowywania jego planu. */
    @GetMapping
    public List<HistoryItem> list(@RequestParam(defaultValue = "200") int limit) {
        return repo.findAllByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(CurrentUser.id(), account.historyCutoff(),
                        PageRequest.of(0, Math.min(Math.max(limit, 1), 500))).stream()
                .map(r -> new HistoryItem(r, r.audioFile != null && storage.exists(r.audioFile)))
                .toList();
    }

    @GetMapping("/{id}/audio")
    public ResponseEntity<byte[]> audio(@PathVariable Long id) {
        var r = repo.findByIdAndUserId(id, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Wpis historii"));
        if (r.audioFile == null || !storage.exists(r.audioFile)) throw ApiException.notFound("Plik audio");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .header("X-Engine-Mode", r.engineMode).body(storage.read(r.audioFile));
    }

    @DeleteMapping
    public void clear() {
        repo.deleteByUserId(CurrentUser.id());
    }
}
