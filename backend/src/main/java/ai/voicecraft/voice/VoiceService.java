package ai.voicecraft.voice;

import ai.voicecraft.account.AccountService;
import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos.*;
import ai.voicecraft.history.GenerationRecord;
import ai.voicecraft.history.HistoryRepository;
import ai.voicecraft.project.GenerationService;
import ai.voicecraft.project.VoiceSettings;
import ai.voicecraft.security.CurrentUser;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceService {
    private final VoiceRepository repo;
    private final GenerationService generation;
    private final EngineClient engine;
    private final AudioStorage storage;
    private final HistoryRepository history;
    private final AccountService account;
    private final VoiceFavoriteRepository favorites;

    public VoiceService(VoiceRepository repo, GenerationService generation, EngineClient engine, AudioStorage storage,
                        HistoryRepository history, AccountService account, VoiceFavoriteRepository favorites) {
        this.account = account;
        this.favorites = favorites;
        this.repo = repo;
        this.generation = generation;
        this.engine = engine;
        this.storage = storage;
        this.history = history;
    }

    /** Usuwa głosy własne i ulubione użytkownika (np. przy usuwaniu konta). */
    @Transactional
    public void deleteAllOf(String userId) {
        favorites.deleteByUserId(userId);
        repo.deleteAll(repo.findAllByOwnerId(userId));
    }

    public List<Voice> list(String q, String language, String category, String gender, String kind, Boolean favorite) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String uid = CurrentUser.id();
        var favs = favorites.findAllByUserId(uid).stream().map(f -> f.voiceId).collect(java.util.stream.Collectors.toSet());
        return repo.findVisible(uid).stream()
                .peek(v -> v.favorite = favs.contains(v.id))
                .filter(v -> query.isEmpty() || (v.name + " " + v.description + " " + String.join(" ", v.traits))
                        .toLowerCase(Locale.ROOT).contains(query))
                .filter(v -> language == null || v.languages.contains(language))
                .filter(v -> category == null || v.categories.contains(category))
                .filter(v -> gender == null || gender.equals(v.gender))
                .filter(v -> kind == null || v.kind.name().equalsIgnoreCase(kind))
                .filter(v -> favorite == null || v.favorite == favorite)
                .sorted(Comparator.comparing((Voice v) -> v.kind != VoiceKind.LIBRARY ? 0 : 1)
                        .thenComparing(v -> v.name))
                .toList();
    }

    /** Głos widoczny dla bieżącego użytkownika (biblioteka lub własny). */
    public Voice get(String id) {
        String uid = CurrentUser.id();
        Voice v = repo.findById(id).filter(x -> x.ownerId == null || x.ownerId.equals(uid))
                .orElseThrow(() -> ApiException.notFound("Głos"));
        v.favorite = favorites.existsByUserIdAndVoiceId(uid, v.id);
        return v;
    }

    @Transactional
    public Voice setFavorite(String id, boolean favorite) {
        Voice v = get(id);
        String uid = CurrentUser.id();
        if (favorite && !v.favorite) favorites.save(new VoiceFavorite(uid, v.id));
        if (!favorite) favorites.deleteByUserIdAndVoiceId(uid, v.id);
        v.favorite = favorite;
        return v;
    }

    @Transactional
    public Voice update(String id, String name, String description) {
        Voice v = get(id);
        if (v.kind == VoiceKind.LIBRARY) throw new ApiException(HttpStatus.FORBIDDEN, "Głosów z biblioteki nie można edytować.");
        if (name != null && !name.isBlank()) v.name = name.trim();
        if (description != null) v.description = description.trim();
        return v;
    }

    @Transactional
    public void delete(String id) {
        Voice v = get(id);
        if (v.kind == VoiceKind.LIBRARY) throw new ApiException(HttpStatus.FORBIDDEN, "Głosów z biblioteki nie można usunąć.");
        favorites.deleteByVoiceId(v.id);
        repo.delete(v);
    }

    /** Próbka głosu – generowana przy pierwszym odsłuchu i ponownie po zmianie trybu silnika. */
    public Audio sample(String id) {
        Voice v = get(id);
        String mode = generation.currentMode();
        if (storage.exists(v.sampleFile) && (mode == null || mode.equals(v.sampleEngineMode))) {
            return new Audio(storage.read(v.sampleFile), v.sampleEngineMode, 0);
        }
        var s = new VoiceSettings();
        s.language = v.languages.isEmpty() ? "pl" : v.languages.getFirst();
        s.voiceId = v.id;
        Audio a = generation.synthesize(v.sampleText == null ? v.description : v.sampleText, s, v);
        Voice stored = repo.findById(v.id).orElseThrow();
        stored.sampleFile = storage.save("sample", a.wav());
        stored.sampleEngineMode = a.engineMode();
        repo.save(stored);
        return a;
    }

    // ---------------------------------------------------------- projektowanie głosu

    public record DesignPreviewRequest(String description, String text, String language, Double expressiveness, Integer seed) {}

    public record DesignPreview(String previewId, long durationMs, String engineMode) {}

    public DesignPreview designPreview(DesignPreviewRequest req) {
        if (req.description() == null || req.description().trim().length() < 10) {
            throw new IllegalArgumentException("Opis głosu musi mieć co najmniej 10 znaków.");
        }
        if (req.text() == null || req.text().isBlank()) throw new IllegalArgumentException("Podaj tekst próbki.");
        account.requireGenerationQuota(req.text().trim().length());
        var s = new VoiceSettings();
        if (req.expressiveness() != null) s.expressiveness = req.expressiveness();
        s.seed = req.seed();
        String lang = req.language() == null ? "pl" : req.language();
        Audio a = generation.design(req.description().trim(), req.text().trim(), lang, s);
        String file = storage.save("design", a.wav());
        var rec = new GenerationRecord();
        rec.userId = CurrentUser.id();
        rec.type = "voice_design";
        rec.textPreview = req.description().trim();
        rec.characters = req.text().trim().length();
        rec.durationMs = a.durationMs();
        rec.engineMode = a.engineMode();
        rec.status = "success";
        rec.audioFile = file;
        history.save(rec);
        return new DesignPreview(file, a.durationMs(), a.engineMode());
    }

    /** Podgląd projektu głosu — tylko dla autora (zapisany w jego historii). */
    public byte[] previewAudio(String previewId) {
        if (!previewId.matches("design_[a-f0-9]{16}\\.wav") || !history.existsByUserIdAndAudioFile(CurrentUser.id(), previewId)
                || !storage.exists(previewId)) {
            throw ApiException.notFound("Próbka");
        }
        return storage.read(previewId);
    }

    public record SaveDesigned(String name, String description, String gender, Integer age, List<String> categories,
                               List<String> traits, String language, String previewId, String sampleText, String engineMode) {}

    @Transactional
    public Voice saveDesigned(SaveDesigned req) {
        if (req.name() == null || req.name().isBlank()) throw new IllegalArgumentException("Podaj nazwę głosu.");
        if (req.description() == null || req.description().isBlank()) throw new IllegalArgumentException("Podaj opis głosu.");
        account.requireCustomVoiceSlot();
        var v = new Voice();
        v.id = "custom-" + UUID.randomUUID().toString().substring(0, 8);
        v.ownerId = CurrentUser.id();
        v.kind = VoiceKind.DESIGNED;
        v.name = req.name().trim();
        v.gender = req.gender();
        v.age = req.age();
        v.languages = List.of(req.language() == null ? "pl" : req.language());
        v.categories = req.categories() == null ? List.of() : req.categories();
        v.traits = req.traits() == null ? List.of() : req.traits();
        v.description = req.description().trim();
        v.enginePrompt = req.description().trim();
        v.sampleText = req.sampleText();
        if (req.previewId() != null && history.existsByUserIdAndAudioFile(v.ownerId, req.previewId()) && storage.exists(req.previewId())) {
            v.sampleFile = req.previewId();
            v.sampleEngineMode = req.engineMode();
        }
        return repo.save(v);
    }

    // ---------------------------------------------------------- klonowanie

    public AudioAnalysis analyze(byte[] file, String filename) {
        return engine.analyze(file, filename);
    }

    @Transactional
    public Voice createClone(byte[] file, String filename, String name, String transcript, String language, boolean consent) {
        if (!consent) throw new ApiException(HttpStatus.BAD_REQUEST, "Potwierdź, że masz zgodę właściciela głosu.");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Podaj nazwę głosu.");
        account.requireCloning();
        account.requireCustomVoiceSlot();
        Health h = engine.requireOnline();
        if (h.capabilities() == null || !h.capabilities().cloning()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Klonowanie wymaga połączenia z modelem VoxCPM2. Silnik działa obecnie w trybie demonstracyjnym.");
        }
        CloneReference ref = engine.createReference(file, filename, transcript, true);
        var v = new Voice();
        v.id = "clone-" + UUID.randomUUID().toString().substring(0, 8);
        v.ownerId = CurrentUser.id();
        v.kind = VoiceKind.CLONED;
        v.name = name.trim();
        v.languages = List.of(language == null ? "pl" : language);
        v.categories = List.of("natural");
        v.description = "Głos sklonowany z nagrania (" + Math.round(ref.durationSec()) + " s).";
        v.referenceId = ref.referenceId();
        v.sampleText = "To jest próbka mojego sklonowanego głosu, wygenerowana przez VoiceCraft AI.";
        return repo.save(v);
    }
}
