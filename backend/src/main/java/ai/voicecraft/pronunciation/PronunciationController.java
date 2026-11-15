package ai.voicecraft.pronunciation;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.project.ProjectService;
import ai.voicecraft.project.SpeechText;
import ai.voicecraft.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Słownik wymowy. Wpisy globalne (projectId = null) działają we wszystkich projektach użytkownika;
 * wpisy projektu mają pierwszeństwo przed globalnymi o tym samym haśle.
 */
@RestController
@RequestMapping("/api/pronunciation")
public class PronunciationController {
    static final int MAX_ENTRIES = 500;

    private final PronunciationRepository repo;
    private final ProjectService projects;

    public PronunciationController(PronunciationRepository repo, ProjectService projects) {
        this.repo = repo;
        this.projects = projects;
    }

    public record EntryDto(String id, String projectId, String term, String replacement, boolean caseSensitive) {
        static EntryDto of(PronunciationEntry e) {
            return new EntryDto(e.id, e.projectId, e.term, e.replacement, e.caseSensitive);
        }
    }

    public record EntryInput(String projectId, String term, String replacement, Boolean caseSensitive) {}

    /** Bez projectId — wszystkie wpisy użytkownika; z projectId — globalne + wpisy projektu. */
    @GetMapping
    public List<EntryDto> list(@RequestParam(required = false) String projectId) {
        String uid = CurrentUser.id();
        if (projectId != null) projects.get(projectId);
        var entries = projectId == null ? repo.findAllOf(uid) : repo.findApplicable(uid, projectId);
        return entries.stream().map(EntryDto::of).toList();
    }

    @PostMapping
    @Transactional
    public EntryDto create(@RequestBody EntryInput in) {
        String uid = CurrentUser.id();
        if (repo.countByOwnerId(uid) >= MAX_ENTRIES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Słownik może mieć najwyżej " + MAX_ENTRIES + " wpisów.");
        }
        var e = new PronunciationEntry();
        e.id = UUID.randomUUID().toString();
        e.ownerId = uid;
        if (in.projectId() != null) {
            projects.get(in.projectId());
            e.projectId = in.projectId();
        }
        apply(e, in);
        ensureUnique(uid, e);
        return EntryDto.of(repo.save(e));
    }

    @PutMapping("/{id}")
    @Transactional
    public EntryDto update(@PathVariable String id, @RequestBody EntryInput in) {
        String uid = CurrentUser.id();
        var e = repo.findByIdAndOwnerId(id, uid).orElseThrow(() -> ApiException.notFound("Wpis słownika"));
        apply(e, in);
        ensureUnique(uid, e);
        return EntryDto.of(e);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable String id) {
        repo.delete(repo.findByIdAndOwnerId(id, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Wpis słownika")));
    }

    private static void apply(PronunciationEntry e, EntryInput in) {
        String term = in.term() == null ? "" : in.term().trim().replaceAll("\\s+", " ");
        String replacement = in.replacement() == null ? "" : in.replacement().trim().replaceAll("\\s+", " ");
        if (term.isEmpty() || term.length() > 100) throw new IllegalArgumentException("Hasło musi mieć od 1 do 100 znaków.");
        if (replacement.isEmpty() || replacement.length() > 300) throw new IllegalArgumentException("Wymowa musi mieć od 1 do 300 znaków.");
        if (term.contains("[") || term.contains("]") || replacement.contains("[") || replacement.contains("]")) {
            throw new IllegalArgumentException("Hasło i wymowa nie mogą zawierać nawiasów kwadratowych.");
        }
        e.term = term;
        e.replacement = replacement;
        e.caseSensitive = Boolean.TRUE.equals(in.caseSensitive());
    }

    private void ensureUnique(String uid, PronunciationEntry e) {
        boolean dup = repo.findAllOf(uid).stream().anyMatch(x -> !x.id.equals(e.id)
                && java.util.Objects.equals(x.projectId, e.projectId)
                && SpeechText.normalizeTerm(x.term).equals(SpeechText.normalizeTerm(e.term)));
        if (dup) throw new ApiException(HttpStatus.CONFLICT, "Hasło „" + e.term + "” jest już w słowniku.");
    }
}
