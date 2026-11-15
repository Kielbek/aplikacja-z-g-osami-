package ai.voicecraft.project;

import ai.voicecraft.account.Account;
import ai.voicecraft.account.AccountService;
import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.audio.WavUtils;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos.Audio;
import ai.voicecraft.history.GenerationRecord;
import ai.voicecraft.history.HistoryRepository;
import ai.voicecraft.pronunciation.PronunciationEntry;
import ai.voicecraft.pronunciation.PronunciationRepository;
import ai.voicecraft.security.CurrentUser;
import ai.voicecraft.project.ProjectDtos.*;
import ai.voicecraft.voice.Voice;
import ai.voicecraft.voice.VoiceRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private final ProjectRepository projects;
    private final VoiceRepository voices;
    private final HistoryRepository history;
    private final AccountService account;
    private final AudioStorage storage;
    private final GenerationService generation;
    private final EngineClient engine;
    private final PronunciationRepository dictionary;

    public ProjectService(ProjectRepository projects, VoiceRepository voices, HistoryRepository history,
                          AccountService account, AudioStorage storage, GenerationService generation, EngineClient engine,
                          PronunciationRepository dictionary) {
        this.dictionary = dictionary;
        this.projects = projects;
        this.voices = voices;
        this.history = history;
        this.account = account;
        this.storage = storage;
        this.generation = generation;
        this.engine = engine;
    }

    // ------------------------------------------------------------ odczyt

    public List<ProjectDto> list() {
        String uid = CurrentUser.id();
        String mode = generation.currentMode();
        Map<String, Voice> vs = voices.findVisible(uid).stream().collect(Collectors.toMap(v -> v.id, Function.identity()));
        List<PronunciationEntry> all = dictionary.findAllOf(uid);
        return projects.findAllByOwnerIdOrderByUpdatedAtDesc(uid).stream()
                .map(p -> toDto(p, mode, vs, false, applicable(all, p.id)))
                .toList();
    }

    public ProjectDto get(String id) {
        return dto(find(id));
    }

    // ------------------------------------------------------------ zapis

    @Transactional
    public ProjectDto create(CreateProject req) {
        account.requireProjectSlot();
        Account a = account.get();
        var p = new Project();
        p.id = UUID.randomUUID().toString();
        p.ownerId = a.userId;
        p.name = req.name() == null || req.name().isBlank() ? "Projekt bez tytułu" : req.name().trim();
        p.settings.language = a.defaultLanguage;
        p.settings.voiceId = canUseVoice(a.defaultVoiceId) ? a.defaultVoiceId : null;
        List<String> parts = req.text() == null ? List.of() : TextSplitter.segments(req.text(), TextSplitter.MAX_SEGMENT);
        if (parts.isEmpty()) parts = List.of("");
        account.requireProjectCharacters(parts.stream().mapToInt(SpeechText::spokenCharacters).sum());
        for (String t : parts) addSegment(p, t);
        return dto(projects.save(p));
    }

    @Transactional
    public ProjectDto update(String id, UpdateProject req) {
        Project p = find(id);
        if (req.name() != null) p.name = req.name().trim();
        if (req.settings() != null) {
            VoiceSettings s = req.settings();
            if (s.voiceId != null && !canUseVoice(s.voiceId)) throw ApiException.notFound("Głos");
            account.requireAdvancedAllowed(p.settings, s);
            s.speed = clamp(s.speed, 0.5, 2.0);
            s.expressiveness = clamp(s.expressiveness, 0, 1);
            s.cfgValue = clamp(s.cfgValue, 0.5, 5);
            s.inferenceTimesteps = (int) clamp(s.inferenceTimesteps, 4, 50);
            p.settings = s;
        }
        p.updatedAt = Instant.now();
        return dto(projects.save(p));
    }

    @Transactional
    public ProjectDto replaceSegments(String id, List<SegmentInput> input) {
        Project p = find(id);
        Map<String, Segment> existing = p.segments.stream().collect(Collectors.toMap(s -> s.id, Function.identity()));
        var next = new ArrayList<Segment>();
        int pos = 0;
        for (SegmentInput in : input) {
            Segment s = existing.remove(in.id());
            if (s == null) {
                s = new Segment();
                s.id = in.id();
                s.project = p;
            }
            if (!Objects.equals(s.text, in.text())) s.error = null;
            s.text = in.text();
            s.voiceId = in.voiceId() != null && canUseVoice(in.voiceId()) ? in.voiceId() : null;
            s.position = pos++;
            next.add(s);
        }
        p.segments.clear();
        p.segments.addAll(next);
        p.updatedAt = Instant.now();
        return dto(projects.save(p));
    }

    @Transactional
    public void delete(String id) {
        projects.delete(find(id));
    }

    @Transactional
    public ProjectDto duplicate(String id) {
        Project src = find(id);
        account.requireProjectSlot();
        var p = new Project();
        p.id = UUID.randomUUID().toString();
        p.ownerId = src.ownerId;
        p.name = src.name + " (kopia)";
        p.settings = src.settings.copy();
        p.lastGeneratedAt = src.lastGeneratedAt;
        for (Segment s : src.segments) {
            Segment c = addSegment(p, s.text);
            c.voiceId = s.voiceId;
            c.audioFile = s.audioFile; // pliki są niezmienne – można je współdzielić
            c.durationMs = s.durationMs;
            c.generatedHash = s.generatedHash;
            c.engineMode = s.engineMode;
            c.generatedAt = s.generatedAt;
        }
        return dto(projects.save(p));
    }

    // ------------------------------------------------------------ generowanie

    /** Generuje pojedynczy fragment. Pozostałe fragmenty projektu pozostają nietknięte. */
    public SegmentDto generateSegment(String projectId, String segmentId) {
        Project p = find(projectId);
        Segment s = p.segments.stream().filter(x -> x.id.equals(segmentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Fragment"));
        String text = s.text.trim();
        if (SpeechText.spokenCharacters(text) == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Fragment jest pusty.");
        int characters = SpeechText.spokenCharacters(text);
        account.requireProjectCharacters(p.segments.stream().mapToInt(x -> SpeechText.spokenCharacters(x.text)).sum());
        account.requireGenerationQuota(characters);
        List<PronunciationEntry> dict = dictionary.findApplicable(p.ownerId, p.id);
        String effective = SpeechText.applyDictionary(text, dict);
        String voiceOverride = s.voiceId != null && canUseVoice(s.voiceId) ? s.voiceId : null;
        String voiceId = voiceOverride != null ? voiceOverride : p.settings.voiceId;
        Voice voice = voiceId == null || !canUseVoice(voiceId) ? null : voices.findById(voiceId).orElse(null);

        var rec = new GenerationRecord();
        rec.userId = p.ownerId;
        rec.type = "segment";
        rec.projectId = p.id;
        rec.projectName = p.name;
        rec.segmentId = s.id;
        rec.voiceId = voice == null ? null : voice.id;
        rec.voiceName = voice == null ? null : voice.name;
        rec.textPreview = text.length() > 380 ? text.substring(0, 380) + "…" : text;
        rec.characters = characters;
        rec.segmentText = text;
        try {
            Audio audio = generation.synthesizeSegment(effective, account.effectiveSettings(p.settings), voice);
            String file = storage.save("seg", audio.wav());
            s.audioFile = file;
            s.durationMs = audio.durationMs();
            s.engineMode = audio.engineMode();
            s.generatedAt = Instant.now();
            s.generatedHash = GenerationService.hash(effective, p.settings, voiceOverride);
            rec.segmentHash = s.generatedHash;
            s.error = null;
            p.lastGeneratedAt = s.generatedAt;
            rec.status = "success";
            rec.durationMs = audio.durationMs();
            rec.engineMode = audio.engineMode();
            rec.audioFile = file;
            if (voice != null) {
                voice.useCount++;
                voices.save(voice);
            }
        } catch (ApiException e) {
            s.error = e.getMessage();
            rec.status = "error";
            rec.error = e.getMessage();
            projects.save(p);
            history.save(rec);
            throw e;
        }
        projects.save(p);
        history.save(rec);
        account.recordUsage(p.ownerId, "segment", characters, rec.durationMs);
        Map<String, Voice> vs = voice == null ? Map.of() : Map.of(voice.id, voice);
        return toDto(p, generation.currentMode(), vs, true, dict).segments().stream()
                .filter(x -> x.id().equals(segmentId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------ wersje fragmentu

    public record SegmentVersion(Long id, Instant createdAt, long durationMs, String voiceName, String engineMode,
                                 String text, boolean current, boolean matchesCurrentText) {}

    /** Poprzednie nagrania fragmentu (każda generacja zapisuje nowy, niezmienny plik). */
    public List<SegmentVersion> versions(String projectId, String segmentId) {
        Project p = find(projectId);
        Segment s = segment(p, segmentId);
        String expected = expectedHash(s, p.settings, dictionary.findApplicable(p.ownerId, p.id));
        return history.findTop30ByUserIdAndSegmentIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        p.ownerId, segmentId, "success", account.historyCutoff()).stream()
                .filter(r -> r.audioFile != null && storage.exists(r.audioFile))
                .map(r -> new SegmentVersion(r.id, r.createdAt, r.durationMs, r.voiceName, r.engineMode,
                        r.segmentText != null ? r.segmentText : r.textPreview,
                        r.audioFile.equals(s.audioFile), expected.equals(r.segmentHash)))
                .toList();
    }

    public byte[] versionAudio(String projectId, String segmentId, Long versionId) {
        Project p = find(projectId);
        var r = history.findByIdAndUserId(versionId, p.ownerId)
                .filter(x -> segmentId.equals(x.segmentId) && x.audioFile != null)
                .orElseThrow(() -> ApiException.notFound("Wersja"));
        return storage.read(r.audioFile);
    }

    /**
     * Przywraca wybraną wersję nagrania. Jeśli wersja powstała dla innego tekstu lub ustawień,
     * fragment będzie oznaczony jako nieaktualny — dokładnie tak, jak było w chwili tamtej generacji.
     */
    public SegmentDto restoreVersion(String projectId, String segmentId, Long versionId) {
        Project p = find(projectId);
        Segment s = segment(p, segmentId);
        var r = history.findByIdAndUserId(versionId, p.ownerId)
                .filter(x -> segmentId.equals(x.segmentId) && "success".equals(x.status) && x.audioFile != null)
                .orElseThrow(() -> ApiException.notFound("Wersja"));
        if (!storage.exists(r.audioFile)) throw ApiException.notFound("Nagranie tej wersji");
        s.audioFile = r.audioFile;
        s.durationMs = r.durationMs;
        s.engineMode = r.engineMode;
        s.generatedAt = r.createdAt;
        s.generatedHash = r.segmentHash;
        s.error = null;
        p.updatedAt = Instant.now();
        projects.save(p);
        return dto(p).segments().stream().filter(x -> x.id().equals(segmentId)).findFirst().orElseThrow();
    }

    public record Subtitles(String content, String filename, String contentType) {}

    /** Napisy SRT/VTT z czasami gotowych fragmentów (zgodne z połączonym nagraniem i eksportem). */
    public Subtitles subtitles(String id, String format) {
        String fmt = "vtt".equals(format) ? "vtt" : "srt";
        Project p = find(id);
        ProjectDto dto = dto(p);
        List<SegmentDto> nonEmpty = dto.segments().stream().filter(x -> !"empty".equals(x.status())).toList();
        if (nonEmpty.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "Projekt nie zawiera tekstu.");
        long pending = nonEmpty.stream().filter(x -> !"ready".equals(x.status())).count();
        if (pending > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Napisy wymagają wygenerowania wszystkich fragmentów (pozostało: " + pending + ").");
        }
        var timings = nonEmpty.stream()
                .map(x -> new ai.voicecraft.subtitles.SubtitleBuilder.SegmentTiming(x.text(), x.durationMs())).toList();
        var cues = ai.voicecraft.subtitles.SubtitleBuilder.cues(timings, account.get().pauseBetweenSegmentsMs);
        String content = "vtt".equals(fmt) ? ai.voicecraft.subtitles.SubtitleBuilder.vtt(cues) : ai.voicecraft.subtitles.SubtitleBuilder.srt(cues);
        return new Subtitles(content, fileName(account.get().fileNamePattern, p.name) + "." + fmt,
                "vtt".equals(fmt) ? "text/vtt" : "application/x-subrip");
    }

    private static Segment segment(Project p, String segmentId) {
        return p.segments.stream().filter(x -> x.id.equals(segmentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Fragment"));
    }

    public byte[] segmentAudio(String projectId, String segmentId) {
        Segment s = find(projectId).segments.stream().filter(x -> x.id.equals(segmentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Fragment"));
        if (!storage.exists(s.audioFile)) throw ApiException.notFound("Nagranie fragmentu");
        return storage.read(s.audioFile);
    }

    public record Rendered(byte[] data, String contentType, String filename, String engineMode, Double loudnessLufs) {}

    /** Łączy gotowe fragmenty w jedno nagranie (bez ponownej syntezy). */
    public Rendered render(String id, String format, boolean recordExport) {
        if (recordExport && format != null) account.requireExportFormat(format);
        Project p = find(id);
        ProjectDto dto = dto(p);
        List<SegmentDto> nonEmpty = dto.segments().stream().filter(s -> !"empty".equals(s.status())).toList();
        if (nonEmpty.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "Projekt nie zawiera tekstu.");
        long pending = nonEmpty.stream().filter(s -> !"ready".equals(s.status())).count();
        if (pending > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    pending + " " + plural(pending, "fragment wymaga", "fragmenty wymagają", "fragmentów wymaga") + " wygenerowania.");
        }
        Map<String, Segment> byId = p.segments.stream().collect(Collectors.toMap(s -> s.id, Function.identity()));
        List<byte[]> wavs = nonEmpty.stream().map(s -> storage.read(byId.get(s.id()).audioFile)).toList();
        Account a = account.get();
        byte[] wav = WavUtils.concat(wavs, a.pauseBetweenSegmentsMs);
        Double loudness = null;
        if (recordExport && a.exportLoudness != null && !"off".equals(a.exportLoudness)) {
            // Normalizacja głośności (ITU-R BS.1770) z limitem szczytu −1 dBFS
            var pcm = WavUtils.decode(wav);
            var result = ai.voicecraft.audio.Loudness.normalize(pcm.samples(), pcm.format().getSampleRate(),
                    Double.parseDouble(a.exportLoudness), -1.0);
            wav = WavUtils.encode(result.samples(), pcm.format().getSampleRate());
            loudness = Double.isInfinite(result.outputLufs()) ? null : Math.round(result.outputLufs() * 10) / 10.0;
        }
        String mode = nonEmpty.stream().anyMatch(s -> "demo".equals(s.engineMode())) ? "demo" : "voxcpm2";
        String fmt = format == null ? a.exportFormat : format;
        if (recordExport) account.requireExportFormat(fmt);
        byte[] out = "mp3".equals(fmt) ? engine.toMp3(wav, account.effectiveBitrate()) : wav;
        if (recordExport) {
            // Oznaczenie treści AI w metadanych pliku (AI Act art. 50)
            var label = ai.voicecraft.audio.AiContentLabel.of(p.name, mode);
            out = "mp3".equals(fmt) ? ai.voicecraft.audio.AiContentLabel.mp3(out, label) : ai.voicecraft.audio.AiContentLabel.wav(out, label);
        }
        String filename = fileName(a.fileNamePattern, p.name) + ("demo".equals(mode) ? "_DEMO" : "") + "." + fmt;
        if (recordExport) {
            var rec = new GenerationRecord();
            rec.userId = p.ownerId;
            rec.type = "export";
            rec.projectId = p.id;
            rec.projectName = p.name;
            rec.voiceName = dto.voice() == null ? null : dto.voice().name();
            rec.textPreview = "Eksport " + fmt.toUpperCase() + " · " + nonEmpty.size() + " fragm.";
            rec.durationMs = dto.stats().durationMs();
            rec.engineMode = mode;
            rec.status = "success";
            history.save(rec);
        }
        return new Rendered(out, "mp3".equals(fmt) ? "audio/mpeg" : "audio/wav", filename, mode, loudness);
    }

    // ------------------------------------------------------------ pomocnicze

    /** Projekt bieżącego użytkownika — cudze projekty są nieodróżnialne od nieistniejących (404). */
    private Project find(String id) {
        return projects.findByIdAndOwnerId(id, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Projekt"));
    }

    /** Głos z biblioteki lub własny głos bieżącego użytkownika. */
    private boolean canUseVoice(String voiceId) {
        if (voiceId == null) return false;
        return voices.findById(voiceId).map(v -> v.ownerId == null || v.ownerId.equals(CurrentUser.id())).orElse(false);
    }

    /** Projekt powitalny dla nowego konta. */
    @Transactional
    public void createWelcomeProject(String userId) {
        CurrentUser.runAs(userId, () -> create(new CreateProject("Mój pierwszy projekt",
                "Witaj w VoiceCraft AI! To jest pierwszy fragment Twojego projektu.\n\n"
                        + "Każdy akapit to osobny fragment. Możesz go poprawić i wygenerować ponownie, nie generując całości od nowa.")));
    }

    /** Usuwa wszystkie projekty użytkownika (np. przy usuwaniu konta). */
    @Transactional
    public void deleteAllOf(String userId) {
        projects.deleteAll(projects.findAllByOwnerIdOrderByUpdatedAtDesc(userId));
    }

    private ProjectDto dto(Project p) {
        Map<String, Voice> vs = p.settings.voiceId == null || !canUseVoice(p.settings.voiceId) ? Map.of()
                : voices.findById(p.settings.voiceId).map(v -> Map.of(v.id, v)).orElse(Map.of());
        return toDto(p, generation.currentMode(), vs, true, dictionary.findApplicable(p.ownerId, p.id));
    }

    /** Wpisy słownika dla projektu (globalne + projektu) z listy wszystkich wpisów użytkownika. */
    private static List<PronunciationEntry> applicable(List<PronunciationEntry> all, String projectId) {
        return all.stream().filter(e -> e.projectId == null || e.projectId.equals(projectId)).toList();
    }

    static String expectedHash(Segment s, VoiceSettings settings, List<PronunciationEntry> dict) {
        return GenerationService.hash(SpeechText.applyDictionary(s.text.trim(), dict), settings, s.voiceId);
    }

    private static Segment addSegment(Project p, String text) {
        var s = new Segment();
        s.id = UUID.randomUUID().toString();
        s.project = p;
        s.text = text;
        s.position = p.segments.size();
        p.segments.add(s);
        return s;
    }

    static String segmentStatus(Segment s, VoiceSettings settings, String currentMode) {
        return segmentStatus(s, settings, currentMode, List.of());
    }

    static String segmentStatus(Segment s, VoiceSettings settings, String currentMode, List<PronunciationEntry> dict) {
        if (s.text == null || s.text.isBlank() || SpeechText.spokenCharacters(s.text) == 0) return "empty";
        if (s.audioFile == null) return s.error != null ? "error" : "draft";
        if (!expectedHash(s, settings, dict).equals(s.generatedHash)) return "stale";
        if (currentMode != null && s.engineMode != null && !currentMode.equals(s.engineMode)) return "stale";
        return s.error != null ? "error" : "ready";
    }

    private ProjectDto toDto(Project p, String mode, Map<String, Voice> vs, boolean withSegments, List<PronunciationEntry> dict) {
        var segs = new ArrayList<SegmentDto>();
        int chars = 0, ready = 0, stale = 0, draft = 0, err = 0, nonEmpty = 0;
        long duration = 0;
        for (Segment s : p.segments) {
            String st = segmentStatus(s, p.settings, mode, dict);
            chars += SpeechText.spokenCharacters(s.text);
            switch (st) {
                case "ready" -> { ready++; duration += s.durationMs; }
                case "stale" -> stale++;
                case "draft" -> draft++;
                case "error" -> err++;
                default -> { }
            }
            if (!"empty".equals(st)) nonEmpty++;
            if (withSegments) {
                segs.add(new SegmentDto(s.id, s.position, s.text, st, s.durationMs, s.engineMode, s.generatedAt, s.error,
                        s.audioFile == null ? null : s.audioFile.substring(Math.max(0, s.audioFile.length() - 20)), s.voiceId));
            }
        }
        String status;
        if (nonEmpty == 0) status = "empty";
        else if (ready == nonEmpty) status = "ready";
        else if (err > 0) status = "error";
        else if (ready > 0 || stale > 0) status = "partial";
        else status = "draft";
        Voice v = p.settings.voiceId == null ? null : vs.get(p.settings.voiceId);
        return new ProjectDto(p.id, p.name, p.createdAt, p.updatedAt, p.lastGeneratedAt, p.settings,
                v == null ? null : new VoiceRef(v.id, v.name),
                new Stats(chars, p.segments.size(), ready, stale, draft, err, duration, status),
                withSegments ? segs : List.of());
    }

    private static String fileName(String pattern, String project) {
        String slug = java.text.Normalizer.normalize(project, java.text.Normalizer.Form.NFD)
                .replace("ł", "l").replace("Ł", "L")
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase();
        if (slug.isEmpty()) slug = "nagranie";
        String p = pattern == null || pattern.isBlank() ? "{project}" : pattern;
        return p.replace("{project}", slug).replace("{date}", LocalDate.now().toString()).replaceAll("[^A-Za-z0-9_.{}-]", "_");
    }

    private static String plural(long n, String one, String few, String many) {
        if (n == 1) return one;
        long d = n % 10, t = n % 100;
        return d >= 2 && d <= 4 && (t < 12 || t > 14) ? few : many;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
