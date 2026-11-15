package ai.voicecraft.voice;

import ai.voicecraft.engine.EngineDtos.AudioAnalysis;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/voices")
public class VoiceController {
    private final VoiceService service;
    private final VoicePreviewService previews;
    private final VoiceCatalog catalog;

    public VoiceController(VoiceService service, VoicePreviewService previews, VoiceCatalog catalog) {
        this.service = service;
        this.previews = previews;
        this.catalog = catalog;
    }

    /** Kategorie zastosowań biblioteki (Audiobooki, Social Media…). */
    @GetMapping("/categories")
    public List<VoiceCatalog.Category> categories() {
        return catalog.categories();
    }

    public record PreviewLanguage(String code, String text) {}

    /** Języki próbek (30 języków VoxCPM2) i zdanie, które wypowiada każdy głos. */
    @GetMapping("/preview-languages")
    public List<PreviewLanguage> previewLanguages() {
        return PreviewTexts.TEXTS.entrySet().stream().map(e -> new PreviewLanguage(e.getKey(), e.getValue())).toList();
    }

    /** Próbka głosu w wybranym języku: gotowy plik (natychmiast) albo wygenerowana raz i zapamiętana. */
    @GetMapping("/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String id, @RequestParam(defaultValue = "pl") String lang) {
        var p = previews.get(id, lang);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .header("X-Engine-Mode", p.engineMode())
                .header("X-Preview-Source", p.source())
                .header("Cache-Control", "private, max-age=3600")
                .body(p.wav());
    }

    @GetMapping
    public List<Voice> list(@RequestParam(required = false) String q, @RequestParam(required = false) String language,
                            @RequestParam(required = false) String category, @RequestParam(required = false) String gender,
                            @RequestParam(required = false) String kind, @RequestParam(required = false) Boolean favorite) {
        return service.list(q, language, category, gender, kind, favorite);
    }

    @GetMapping("/{id}")
    public Voice get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}/favorite")
    public Voice favorite(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        return service.setFavorite(id, Boolean.TRUE.equals(body.get("favorite")));
    }

    @PatchMapping("/{id}")
    public Voice update(@PathVariable String id, @RequestBody Map<String, String> body) {
        return service.update(id, body.get("name"), body.get("description"));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    @GetMapping("/{id}/sample")
    public ResponseEntity<byte[]> sample(@PathVariable String id) {
        var a = service.sample(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .header("X-Engine-Mode", a.engineMode()).body(a.wav());
    }

    @PostMapping("/design/preview")
    public VoiceService.DesignPreview designPreview(@RequestBody VoiceService.DesignPreviewRequest req) {
        return service.designPreview(req);
    }

    @GetMapping("/design/preview/{previewId}")
    public ResponseEntity<byte[]> previewAudio(@PathVariable String previewId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(service.previewAudio(previewId));
    }

    @PostMapping("/design")
    public Voice saveDesigned(@RequestBody VoiceService.SaveDesigned req) {
        return service.saveDesigned(req);
    }

    @PostMapping(value = "/clone/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AudioAnalysis analyze(@RequestPart("file") MultipartFile file) throws IOException {
        return service.analyze(file.getBytes(), file.getOriginalFilename());
    }

    @PostMapping(value = "/clone", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Voice clone(@RequestPart("file") MultipartFile file, @RequestParam String name,
                       @RequestParam(defaultValue = "") String transcript, @RequestParam(defaultValue = "pl") String language,
                       @RequestParam(defaultValue = "false") boolean consent,
                       @RequestParam(required = false) String consentVersion,
                       HttpServletRequest http) throws IOException {
        return service.createClone(file.getBytes(), file.getOriginalFilename(), name, transcript, language, consent,
                consentVersion, new VoiceService.RequestContext(http.getRemoteAddr(), http.getHeader("User-Agent")));
    }

    /** Aktualna treść oświadczenia o zgodzie na klonowanie (wyświetlana w formularzu). */
    @GetMapping("/clone/consent")
    public VoiceService.ConsentInfo cloningConsent() {
        return service.cloningConsent();
    }

    /** Zapisana zgoda dla sklonowanego głosu (brak treści dla innych głosów). */
    @GetMapping("/{id}/consent")
    public ResponseEntity<VoiceService.ConsentDto> consent(@PathVariable String id) {
        var c = service.consentOf(id);
        return c == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(c);
    }
}
