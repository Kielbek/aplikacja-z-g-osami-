package ai.voicecraft.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Kontrakt serwisu silnika TTS (tts-engine/app/schemas.py). */
public final class EngineDtos {
    private EngineDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capabilities(
            @JsonProperty("voice_design") boolean voiceDesign,
            boolean cloning,
            @JsonProperty("style_prompt") boolean stylePrompt,
            boolean emotion,
            boolean speed,
            List<String> languages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Health(
            String status,
            String mode,
            String model,
            String device,
            @JsonProperty("sample_rate") int sampleRate,
            Capabilities capabilities,
            String message) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VoiceSpec(
            String id,
            String description,
            @JsonProperty("reference_id") String referenceId,
            String gender,
            Integer age) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Params(
            String style,
            String emotion,
            double speed,
            double expressiveness,
            @JsonProperty("cfg_value") double cfgValue,
            @JsonProperty("inference_timesteps") int inferenceTimesteps,
            boolean normalize,
            boolean denoise,
            Integer seed,
            @JsonProperty("ultimate_cloning") boolean ultimateCloning) {}

    public record SynthesizeRequest(String text, String language, VoiceSpec voice, Params params) {}

    public record VoiceDesignRequest(String description, String text, String language, Params params) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AudioAnalysis(
            @JsonProperty("duration_sec") double durationSec,
            @JsonProperty("sample_rate") int sampleRate,
            int channels,
            @JsonProperty("peak_dbfs") double peakDbfs,
            @JsonProperty("rms_dbfs") double rmsDbfs,
            @JsonProperty("clipping_ratio") double clippingRatio,
            @JsonProperty("silence_ratio") double silenceRatio,
            @JsonProperty("estimated_snr_db") double estimatedSnrDb,
            List<String> issues,
            boolean suitable) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CloneReference(@JsonProperty("reference_id") String referenceId, @JsonProperty("duration_sec") double durationSec) {}

    /** Wynik syntezy: WAV + tryb silnika, który go wygenerował. */
    public record Audio(byte[] wav, String engineMode, long durationMs) {}
}
