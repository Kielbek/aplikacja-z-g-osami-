package ai.voicecraft.publicapi;

import ai.voicecraft.account.AccountService;
import ai.voicecraft.account.SubscriptionPlan;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.engine.GpuQueue;
import ai.voicecraft.security.CurrentUser;
import ai.voicecraft.voice.Voice;
import ai.voicecraft.voice.VoiceRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Publiczne API Narrivy (wersja 1). Uwierzytelnianie kluczem API ({@link ApiKeyFilter}),
 * opis w formacie OpenAPI: {@code GET /v1/openapi.json}.
 */
@RestController
@RequestMapping("/v1")
public class V1Controller {
    private final SpeechService speech;
    private final ApiJobService jobs;
    private final ApiRateLimiter limiter;
    private final AccountService accounts;
    private final VoiceRepository voices;

    public V1Controller(SpeechService speech, ApiJobService jobs, ApiRateLimiter limiter, AccountService accounts, VoiceRepository voices) {
        this.speech = speech;
        this.jobs = jobs;
        this.limiter = limiter;
        this.accounts = accounts;
        this.voices = voices;
    }

    // ------------------------------------------------------------ synteza

    /** Synteza synchroniczna: odpowiedzią jest plik audio (do {@value SpeechService#MAX_SYNC_CHARACTERS} znaków). */
    @PostMapping(value = "/text-to-speech", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> textToSpeech(@RequestBody String json, HttpServletRequest req) {
        SpeechRequest body = parse(json);
        if (body.callbackUrl() != null) {
            throw new IllegalArgumentException("Pole „callback_url” dotyczy zadań — użyj POST /v1/text-to-speech/jobs.");
        }
        String uid = CurrentUser.id();
        var prepared = speech.prepare(body.text(), body.voiceId(), body.language(), body.format(), body.settings(),
                SpeechService.MAX_SYNC_CHARACTERS);
        SubscriptionPlan.ApiLimits limits = accounts.get(uid).planId.api();
        if (!limiter.tryEnter(uid, limits.concurrency())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Przekroczono limit " + limits.concurrency()
                    + " równoległych syntez w Twoim planie. Poczekaj na zakończenie trwających żądań albo użyj zadań w tle.",
                    "concurrency_limit_exceeded");
        }
        try {
            var r = speech.render(prepared, keyId(req), GpuQueue.Priority.API);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(r.contentType()))
                    .header("Content-Disposition", "inline; filename=\"narrivy." + r.format() + "\"")
                    .header("X-Characters", String.valueOf(r.characters()))
                    .header("X-Duration-Ms", String.valueOf(r.durationMs()))
                    .header("X-Engine-Mode", r.engineMode() == null ? "unknown" : r.engineMode())
                    .body(r.audio());
        } finally {
            limiter.exit(uid);
        }
    }

    /** Zadanie w tle — dla długich tekstów (do limitu znaków w projekcie z planu). Odpowiedź 202 z opisem zadania. */
    @PostMapping(value = "/text-to-speech/jobs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createJob(@RequestBody String json, HttpServletRequest req,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        SpeechRequest body = parse(json);
        int max = accounts.get(CurrentUser.id()).planId.maxCharactersPerProject;
        ApiJob j = jobs.create(body, keyId(req), idempotencyKey, max);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", "/v1/text-to-speech/jobs/" + j.id)
                .body(ApiJobService.dto(j));
    }

    @GetMapping("/text-to-speech/jobs/{id}")
    public Map<String, Object> job(@PathVariable String id) {
        return ApiJobService.dto(jobs.get(id));
    }

    @GetMapping("/text-to-speech/jobs/{id}/audio")
    public ResponseEntity<byte[]> jobAudio(@PathVariable String id) {
        ApiJob j = jobs.get(id);
        byte[] audio = jobs.audio(j);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("mp3".equals(j.outputFormat) ? "audio/mpeg" : "audio/wav"))
                .header("Content-Disposition", "attachment; filename=\"" + j.id + "." + j.outputFormat + "\"")
                .header("X-Duration-Ms", String.valueOf(j.durationMs))
                .body(audio);
    }

    // ------------------------------------------------------------ głosy i zużycie

    @GetMapping("/voices")
    public Map<String, Object> voices(@RequestParam(required = false) String language,
                                      @RequestParam(required = false) String category) {
        String uid = CurrentUser.id();
        String lang = language == null ? null : language.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> data = voices.findVisible(uid).stream()
                .filter(v -> lang == null || v.languages.contains(lang))
                .filter(v -> category == null || v.categories.contains(category))
                .sorted(Comparator.comparing((Voice v) -> v.ownerId == null ? 1 : 0).thenComparing(v -> v.name))
                .map(V1Controller::voice)
                .toList();
        return Map.of("object", "list", "data", data);
    }

    @GetMapping("/voices/{id}")
    public Map<String, Object> voice(@PathVariable String id) {
        String uid = CurrentUser.id();
        return voices.findById(id).filter(v -> v.ownerId == null || v.ownerId.equals(uid)).map(V1Controller::voice)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Głos „" + id + "” nie istnieje.", "voice_not_found"));
    }

    @GetMapping("/usage")
    public Map<String, Object> usage() {
        Map<String, Object> u = accounts.usage();
        @SuppressWarnings("unchecked")
        var plan = (SubscriptionPlan.Dto) u.get("plan");
        long used = ((Number) u.get("charactersUsed")).longValue();
        long limit = ((Number) u.get("characterLimit")).longValue();
        long extra = ((Number) u.get("extraCharacters")).longValue();
        var m = new LinkedHashMap<String, Object>();
        m.put("object", "usage");
        m.put("plan", plan.id().toLowerCase(Locale.ROOT));
        m.put("period_start", u.get("periodStart"));
        m.put("period_end", u.get("limitsResetOn"));
        m.put("characters_used", used);
        m.put("character_limit", limit);
        m.put("extra_characters", extra);
        m.put("characters_remaining", Math.max(0, limit - used) + extra);
        m.put("rate_limits", Map.of("requests_per_minute", plan.apiRequestsPerMinute(), "concurrent_requests", plan.apiConcurrency(),
                "max_sync_characters", SpeechService.MAX_SYNC_CHARACTERS, "max_job_characters", plan.maxCharactersPerProject()));
        return m;
    }

    /** Opis API w formacie OpenAPI 3.1 (bez uwierzytelniania). */
    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> openapi() throws java.io.IOException {
        return ResponseEntity.ok().header("Cache-Control", "public, max-age=300")
                .body(new ClassPathResource("openapi/narrivy-v1.json").getContentAsByteArray());
    }

    // ------------------------------------------------------------ pomocnicze

    /** Ścisłe parsowanie: nieznane pole (np. literówka „voiceId”) to błąd, a nie cicho pominięta wartość. */
    private static final tools.jackson.databind.json.JsonMapper STRICT = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    static SpeechRequest parse(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Brak treści żądania (JSON).");
        try {
            return STRICT.readValue(json, SpeechRequest.class);
        } catch (tools.jackson.databind.exc.UnrecognizedPropertyException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Nieznane pole „" + e.getPropertyName()
                    + "”. Dozwolone: text, voice_id, language, format, style, emotion, speed, expressiveness, seed, callback_url.", "invalid_request");
        } catch (tools.jackson.core.JacksonException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Treść żądania nie jest poprawnym JSON-em lub ma pola niewłaściwego typu.", "invalid_json");
        }
    }

    private static String keyId(HttpServletRequest req) {
        Object id = req.getAttribute(ApiKeyFilter.KEY_ID);
        return id == null ? null : id.toString();
    }

    static Map<String, Object> voice(Voice v) {
        var m = new LinkedHashMap<String, Object>();
        m.put("voice_id", v.id);
        m.put("object", "voice");
        m.put("name", v.name);
        m.put("type", v.kind.name().toLowerCase(Locale.ROOT));
        m.put("description", v.description);
        m.put("gender", v.gender);
        m.put("age", v.age);
        m.put("languages", v.languages);
        m.put("categories", v.categories);
        m.put("default_instruction", v.defaultInstruction);
        return m;
    }
}
