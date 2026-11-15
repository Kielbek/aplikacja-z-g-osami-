package ai.voicecraft.engine;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import ai.voicecraft.engine.EngineDtos.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Jedyny punkt styku backendu z silnikiem TTS (serwis Python / VoxCPM2).
 * Reszta aplikacji nie zna szczegółów protokołu.
 */
@Component
public class EngineClient {
    private final RestClient http;
    private final String baseUrl;
    private volatile Health cachedHealth;
    private volatile Instant cachedAt = Instant.EPOCH;

    public EngineClient(AppProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.engine().connectTimeout());
        factory.setReadTimeout(props.engine().readTimeout());
        this.baseUrl = props.engine().url();
        var builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        String key = props.engine().apiKey();
        if (key != null && !key.isBlank()) builder.defaultHeader("Authorization", "Bearer " + key.trim());
        this.http = builder.build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Stan silnika; null gdy serwis jest nieosiągalny. Wynik buforowany na 5 s. */
    public Health health(boolean force) {
        if (!force && Instant.now().isBefore(cachedAt.plusSeconds(5))) return cachedHealth;
        Health h;
        try {
            h = http.get().uri("/health").retrieve().body(Health.class);
        } catch (Exception e) {
            h = null;
        }
        cachedHealth = h;
        cachedAt = Instant.now();
        return h;
    }

    public Health requireOnline() {
        Health h = health(false);
        if (h == null) h = health(true);
        if (h == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Silnik TTS jest niedostępny (" + baseUrl + "). Uruchom serwis tts-engine.");
        }
        return h;
    }

    public Audio synthesize(SynthesizeRequest req) {
        return audio(() -> http.post().uri("/v1/synthesize").contentType(MediaType.APPLICATION_JSON).body(req)
                .retrieve().toEntity(byte[].class));
    }

    public Audio design(VoiceDesignRequest req) {
        return audio(() -> http.post().uri("/v1/voice-design").contentType(MediaType.APPLICATION_JSON).body(req)
                .retrieve().toEntity(byte[].class));
    }

    public AudioAnalysis analyze(byte[] file, String filename) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", named(file, filename));
        return call(() -> http.post().uri("/v1/clone/analyze").contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().body(AudioAnalysis.class));
    }

    public CloneReference createReference(byte[] file, String filename, String transcript, boolean consent) {
        return createReference(file, filename, transcript, consent, null, false);
    }

    /**
     * Rejestruje nagranie referencyjne w silniku.
     *
     * @param referenceId stały identyfikator (ponowna rejestracja tego samego głosu po restarcie silnika) lub null
     * @param trusted     nagranie pochodzi z aplikacji (wygenerowane wzorcowe) — silnik pomija ocenę jakości
     */
    public CloneReference createReference(byte[] file, String filename, String transcript, boolean consent,
                                          String referenceId, boolean trusted) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", named(file, filename));
        form.add("transcript", transcript == null ? "" : transcript);
        form.add("consent", String.valueOf(consent));
        if (referenceId != null) form.add("reference_id", referenceId);
        if (trusted) form.add("trusted", "true");
        return call(() -> http.post().uri("/v1/clone/references").contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().body(CloneReference.class));
    }

    public byte[] toMp3(byte[] wav, int bitrate) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", named(wav, "audio.wav"));
        form.add("bitrate", String.valueOf(bitrate));
        return call(() -> http.post().uri("/v1/encode/mp3").contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().body(byte[].class));
    }

    // ------------------------------------------------------------------

    private Audio audio(java.util.function.Supplier<ResponseEntity<byte[]>> fn) {
        ResponseEntity<byte[]> res = call(fn);
        String mode = res.getHeaders().getFirst("X-Engine-Mode");
        String dur = res.getHeaders().getFirst("X-Duration-Ms");
        return new Audio(res.getBody(), mode == null ? "unknown" : mode, dur == null ? 0 : Long.parseLong(dur));
    }

    private <T> T call(java.util.function.Supplier<T> fn) {
        try {
            return fn.get();
        } catch (RestClientResponseException e) {
            String detail = extractDetail(e);
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            throw new ApiException(status == null ? HttpStatus.BAD_GATEWAY : status, detail);
        } catch (ResourceAccessException e) {
            cachedAt = Instant.EPOCH;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Brak połączenia z silnikiem TTS (" + baseUrl + ").");
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractDetail(RestClientResponseException e) {
        try {
            Map<String, Object> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("detail") != null) return String.valueOf(body.get("detail"));
        } catch (Exception ignored) {
            // treść nie jest JSON-em
        }
        return "Silnik TTS zwrócił błąd " + e.getStatusCode().value() + ".";
    }

    private static ByteArrayResource named(byte[] data, String filename) {
        return new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename == null ? "audio.wav" : filename;
            }
        };
    }
}
