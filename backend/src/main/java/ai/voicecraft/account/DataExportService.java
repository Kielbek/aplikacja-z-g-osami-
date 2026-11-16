package ai.voicecraft.account;

import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.history.HistoryRepository;
import ai.voicecraft.project.ProjectDtos.ProjectDto;
import ai.voicecraft.project.ProjectDtos.SegmentDto;
import ai.voicecraft.project.ProjectRepository;
import ai.voicecraft.project.ProjectService;
import ai.voicecraft.pronunciation.PronunciationRepository;
import ai.voicecraft.security.CurrentUser;
import ai.voicecraft.usage.UsageRepository;
import ai.voicecraft.user.User;
import ai.voicecraft.user.UserRepository;
import ai.voicecraft.voice.Voice;
import ai.voicecraft.voice.VoiceConsentRepository;
import ai.voicecraft.voice.VoiceFavoriteRepository;
import ai.voicecraft.voice.VoiceRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Eksport danych użytkownika (RODO art. 15 — prawo dostępu, art. 20 — przenoszenie danych).
 * Archiwum ZIP: dane w JSON (konto, projekty, głosy, historia, zużycie, słownik, zgody) oraz pliki audio
 * bieżących fragmentów i nagrania wzorcowe własnych głosów.
 */
@Service
public class DataExportService {
    private final UserRepository users;
    private final AccountService accounts;
    private final ProjectRepository projectRepo;
    private final ProjectService projects;
    private final VoiceRepository voices;
    private final VoiceFavoriteRepository favorites;
    private final HistoryRepository history;
    private final UsageRepository usage;
    private final PronunciationRepository dictionary;
    private final VoiceConsentRepository consents;
    private final AudioStorage storage;
    private final JsonMapper json;
    private final ai.voicecraft.project.ProjectFolderRepository folders;

    public DataExportService(UserRepository users, AccountService accounts, ProjectRepository projectRepo, ProjectService projects,
                             VoiceRepository voices, VoiceFavoriteRepository favorites, HistoryRepository history,
                             UsageRepository usage, PronunciationRepository dictionary, VoiceConsentRepository consents,
                             AudioStorage storage, JsonMapper json, ai.voicecraft.project.ProjectFolderRepository folders) {
        this.folders = folders;
        this.users = users;
        this.accounts = accounts;
        this.projectRepo = projectRepo;
        this.projects = projects;
        this.voices = voices;
        this.favorites = favorites;
        this.history = history;
        this.usage = usage;
        this.dictionary = dictionary;
        this.consents = consents;
        this.storage = storage;
        this.json = json;
    }

    /** Dane zebrane w wątku żądania (kontekst zalogowanego użytkownika); zapis ZIP może biec asynchronicznie. */
    public record Prepared(Map<String, Object> documents, Map<String, String> audio) {}

    public Prepared prepare() {
        String uid = CurrentUser.id();
        User u = users.findById(uid).orElseThrow();
        var docs = new LinkedHashMap<String, Object>();
        var audio = new LinkedHashMap<String, String>();

        var user = new LinkedHashMap<String, Object>();
        user.put("id", u.id);
        user.put("email", u.email);
        user.put("fullName", u.fullName);
        user.put("googleAccountLinked", u.googleSub != null);
        user.put("createdAt", u.createdAt);
        user.put("lastLoginAt", u.lastLoginAt);
        user.put("emailVerifiedAt", u.emailVerifiedAt);
        user.put("termsAcceptedAt", u.termsAcceptedAt);
        user.put("termsVersion", u.termsVersion);
        docs.put("konto.json", Map.of("user", user, "account", accounts.get(uid), "usage", accounts.usage()));

        var projectDocs = new ArrayList<ProjectDto>();
        for (var p : projectRepo.findAllByOwnerIdOrderByUpdatedAtDesc(uid)) {
            ProjectDto dto = projects.get(p.id);
            projectDocs.add(dto);
            var byId = new LinkedHashMap<String, String>();
            p.segments.forEach(s -> byId.put(s.id, s.audioFile));
            for (SegmentDto s : dto.segments()) {
                String file = byId.get(s.id());
                if (file != null) audio.put("audio/projekty/" + p.id + "/" + String.format("%03d", s.position() + 1) + "_" + s.id() + ".wav", file);
            }
        }
        docs.put("projekty.json", projectDocs);
        docs.put("foldery.json", folders.findAllByOwnerIdOrderByNameAsc(uid).stream()
                .map(f -> Map.of("id", f.id, "name", f.name, "createdAt", f.createdAt)).toList());

        List<Voice> own = voices.findAllByOwnerId(uid);
        docs.put("glosy.json", Map.of(
                "ownVoices", own,
                "favoriteVoiceIds", favorites.findAllByUserId(uid).stream().map(f -> f.voiceId).toList()));
        for (Voice v : own) {
            if (v.referenceFile != null) audio.put("audio/glosy/" + v.id + "_nagranie_wzorcowe.wav", v.referenceFile);
            if (v.sampleFile != null && !v.sampleFile.equals(v.referenceFile)) audio.put("audio/glosy/" + v.id + "_probka.wav", v.sampleFile);
        }
        docs.put("historia.json", history.findAllByUserIdOrderByCreatedAtDesc(uid));
        docs.put("zuzycie.json", usage.findAllByUserIdOrderByCreatedAtDesc(uid));
        docs.put("pakiety_znakow.json", accounts.creditPurchases(uid));
        docs.put("slownik_wymowy.json", dictionary.findAllOf(uid));
        docs.put("zgody_klonowanie.json", consents.findAllByUserIdOrderByCreatedAtDesc(uid));
        return new Prepared(docs, audio);
    }

    public void write(Prepared data, OutputStream target) throws IOException {
        try (var zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
            put(zip, "CZYTAJ.txt", ("Eksport danych konta Narrivy\nWygenerowano: " + Instant.now() + "\n\n"
                    + "konto.json — profil, ustawienia, plan i zużycie limitów\n"
                    + "projekty.json — projekty z fragmentami tekstu i ustawieniami\n"
                    + "foldery.json — foldery projektów\n"
                    + "glosy.json — własne głosy (zaprojektowane i sklonowane) oraz ulubione\n"
                    + "historia.json — historia generowania\n"
                    + "zuzycie.json — zdarzenia zużycia limitów\n"
                    + "slownik_wymowy.json — słownik wymowy\n"
                    + "zgody_klonowanie.json — zapisane oświadczenia o zgodzie na klonowanie\n"
                    + "audio/ — nagrania fragmentów i nagrania wzorcowe głosów (WAV)\n\n"
                    + "Nagrania audio zostały wygenerowane przez AI (syntetyczny głos).\n").getBytes(StandardCharsets.UTF_8));
            for (var e : data.documents().entrySet()) {
                put(zip, e.getKey(), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(e.getValue()));
            }
            for (var e : data.audio().entrySet()) {
                if (!storage.exists(e.getValue())) continue;
                put(zip, e.getKey(), storage.read(e.getValue()));
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
