package ai.voicecraft.project;

import ai.voicecraft.project.ProjectDtos.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectDto> list() {
        return service.list();
    }

    @PostMapping
    public ProjectDto create(@Valid @RequestBody(required = false) CreateProject req) {
        return service.create(req == null ? new CreateProject(null, null) : req);
    }

    @GetMapping("/{id}")
    public ProjectDto get(@PathVariable String id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public ProjectDto update(@PathVariable String id, @Valid @RequestBody UpdateProject req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    @PostMapping("/{id}/duplicate")
    public ProjectDto duplicate(@PathVariable String id) {
        return service.duplicate(id);
    }

    @PutMapping("/{id}/segments")
    public ProjectDto segments(@PathVariable String id, @Valid @RequestBody List<SegmentInput> segments) {
        return service.replaceSegments(id, segments);
    }

    @PostMapping("/{id}/segments/{segmentId}/generate")
    public SegmentDto generate(@PathVariable String id, @PathVariable String segmentId) {
        return service.generateSegment(id, segmentId);
    }

    @GetMapping("/{id}/segments/{segmentId}/audio")
    public ResponseEntity<byte[]> segmentAudio(@PathVariable String id, @PathVariable String segmentId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .body(service.segmentAudio(id, segmentId));
    }

    /** Połączone nagranie do odsłuchu w odtwarzaczu (WAV). */
    @GetMapping("/{id}/audio")
    public ResponseEntity<byte[]> merged(@PathVariable String id) {
        var r = service.render(id, "wav", false);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.contentType()))
                .header("X-Engine-Mode", r.engineMode()).body(r.data());
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(required = false) String format) {
        if (format != null && !format.equals("mp3") && !format.equals("wav")) {
            throw new IllegalArgumentException("Dozwolone formaty: mp3, wav.");
        }
        var r = service.render(id, format, true);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.contentType()))
                .header("X-Engine-Mode", r.engineMode())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(r.filename(), StandardCharsets.UTF_8).build().toString())
                .body(r.data());
    }
}
