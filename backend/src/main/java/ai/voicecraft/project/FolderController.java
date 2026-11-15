package ai.voicecraft.project;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Foldery projektów: tworzenie, zmiana nazwy, usuwanie (projekty wracają do „Wszystkich projektów”). */
@RestController
@RequestMapping("/api/folders")
public class FolderController {
    static final int MAX_FOLDERS = 100;

    private final ProjectFolderRepository folders;
    private final ProjectRepository projects;

    public FolderController(ProjectFolderRepository folders, ProjectRepository projects) {
        this.folders = folders;
        this.projects = projects;
    }

    public record FolderDto(String id, String name, Instant createdAt) {
        static FolderDto of(ProjectFolder f) {
            return new FolderDto(f.id, f.name, f.createdAt);
        }
    }

    @GetMapping
    public List<FolderDto> list() {
        return folders.findAllByOwnerIdOrderByNameAsc(CurrentUser.id()).stream().map(FolderDto::of).toList();
    }

    @PostMapping
    @Transactional
    public FolderDto create(@RequestBody Map<String, String> body) {
        String uid = CurrentUser.id();
        if (folders.countByOwnerId(uid) >= MAX_FOLDERS) {
            throw new ApiException(HttpStatus.CONFLICT, "Możesz utworzyć najwyżej " + MAX_FOLDERS + " folderów.");
        }
        var f = new ProjectFolder();
        f.id = UUID.randomUUID().toString();
        f.ownerId = uid;
        f.name = name(body.get("name"));
        return FolderDto.of(folders.save(f));
    }

    @PatchMapping("/{id}")
    @Transactional
    public FolderDto rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        ProjectFolder f = find(id);
        f.name = name(body.get("name"));
        return FolderDto.of(f);
    }

    /** Usuwa folder; projekty z folderu nie są usuwane — trafiają z powrotem do „Wszystkich projektów”. */
    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable String id) {
        ProjectFolder f = find(id);
        projects.clearFolder(f.id, f.ownerId);
        folders.delete(f);
    }

    private ProjectFolder find(String id) {
        return folders.findByIdAndOwnerId(id, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Folder"));
    }

    private static String name(String raw) {
        String n = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new IllegalArgumentException("Podaj nazwę folderu.");
        if (n.length() > 80) throw new IllegalArgumentException("Nazwa folderu może mieć najwyżej 80 znaków.");
        return n;
    }
}
