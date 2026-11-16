package ai.voicecraft.publicapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Treść żądania syntezy w publicznym API (pola w snake_case).
 *
 * @param text           tekst do przeczytania; znaczniki pauz {@code [pauza 1s]} są obsługiwane
 * @param voiceId        identyfikator głosu z {@code GET /v1/voices}
 * @param language       kod języka (np. "pl", "en"); domyślnie pierwszy język głosu
 * @param format         "mp3" (domyślnie) lub "wav"
 * @param style          opis sposobu mówienia, np. "spokojna narracja, wyraźna dykcja"
 * @param emotion        emocja, np. "Happy", "Calm", "Whisper"
 * @param speed          tempo 0.5–2.0 (domyślnie 1.0)
 * @param expressiveness ekspresja 0.0–1.0 (domyślnie 0.5)
 * @param seed           ziarno losowości — powtarzalny wynik
 * @param callbackUrl    (tylko zadania) adres HTTPS, na który wyślemy powiadomienie o zakończeniu
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SpeechRequest(
        String text,
        @JsonProperty("voice_id") String voiceId,
        String language,
        String format,
        String style,
        String emotion,
        Double speed,
        Double expressiveness,
        Integer seed,
        @JsonProperty("callback_url") String callbackUrl) {

    /** Parametry zapisywane przy zadaniu (bez tekstu). */
    public record Settings(String style, String emotion, Double speed, Double expressiveness, Integer seed) {}

    Settings settings() {
        return new Settings(style, emotion, speed, expressiveness, seed);
    }
}
