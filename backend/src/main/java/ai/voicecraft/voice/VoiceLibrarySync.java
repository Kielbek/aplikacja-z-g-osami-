package ai.voicecraft.voice;

import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Synchronizuje bibliotekę głosów z katalogiem (catalog.json) przy każdym starcie:
 * <ul>
 *   <li>dodaje / aktualizuje głosy biblioteki (nazwa, opis, kategoria, opis Voice Design, instrukcja),</li>
 *   <li>wczytuje nagrania wzorcowe (Master Prompt) z {@code voicecraft.voices.prompts-dir}, jeśli istnieją —
 *       zmieniony plik unieważnia referencję w silniku i wygenerowane próbki,</li>
 *   <li>przepina projekty, fragmenty, ulubione i ustawienia z poprzedniej biblioteki na najbliższe nowe głosy
 *       i usuwa głosy biblioteki, których nie ma w katalogu.</li>
 * </ul>
 * Brak pliku wzorcowego nie jest błędem: przy pierwszym użyciu z VoxCPM2 nagranie powstanie z opisu (Voice Design)
 * ze stałym seedem.
 */
@Service
public class VoiceLibrarySync {
    private static final Logger log = LoggerFactory.getLogger(VoiceLibrarySync.class);

    /** Wszystkie 30 języków VoxCPM2; pierwszy = język nagrania wzorcowego. */
    public static final List<String> LANGUAGES = List.of("pl", "en", "de", "fr", "es", "it", "pt", "nl", "da", "sv", "no", "fi",
            "ru", "el", "tr", "ar", "he", "hi", "id", "ms", "tl", "vi", "th", "lo", "km", "my", "zh", "ja", "ko", "sw");

    /** Poprzednia biblioteka → najbliższy głos z nowego katalogu. */
    static final Map<String, String> LEGACY = Map.ofEntries(
            Map.entry("pl-anna", "voice_warm_velvet"), Map.entry("pl-tomasz", "voice_warm_narrator"),
            Map.entry("pl-julia", "voice_young_influencer"), Map.entry("pl-marek", "voice_radio_presenter_m"),
            Map.entry("pl-zofia", "voice_kind_grandma"), Map.entry("pl-kacper", "voice_casual_podcast"),
            Map.entry("pl-ewa", "voice_relax_velvet"), Map.entry("pl-adam", "voice_deep_trailer"),
            Map.entry("en-claire", "voice_corporate_f"), Map.entry("en-james", "voice_warm_narrator"),
            Map.entry("en-maya", "voice_young_influencer"), Map.entry("de-lukas", "voice_elearning_m"),
            Map.entry("fr-camille", "voice_luxury_sensual"), Map.entry("es-diego", "voice_gamer_hype"),
            Map.entry("it-giulia", "voice_news_anchor_f"), Map.entry("pt-ines", "voice_corporate_f"));

    private final VoiceCatalog catalog;
    private final VoiceRepository voices;
    private final VoicePreviewRepository previews;
    private final AudioStorage storage;
    private final JdbcTemplate jdbc;
    private final Path promptsDir;

    public VoiceLibrarySync(VoiceCatalog catalog, VoiceRepository voices, VoicePreviewRepository previews, AudioStorage storage,
                            JdbcTemplate jdbc, AppProperties props) {
        this.catalog = catalog;
        this.voices = voices;
        this.previews = previews;
        this.storage = storage;
        this.jdbc = jdbc;
        this.promptsDir = props.voices().promptsDir().toAbsolutePath().normalize();
    }

    @Transactional
    public void sync() {
        Map<String, byte[]> promptCache = new LinkedHashMap<>();
        int imported = 0;
        for (VoiceCatalog.Entry e : catalog.voices()) {
            Voice v = voices.findById(e.id()).orElseGet(Voice::new);
            boolean isNew = v.id == null;
            v.id = e.id();
            v.kind = VoiceKind.LIBRARY;
            v.ownerId = null;
            v.name = e.name();
            v.gender = e.gender();
            v.age = e.age();
            v.languages = new ArrayList<>(LANGUAGES);
            v.categories = List.of(e.category());
            v.traits = e.traits() == null ? List.of() : e.traits();
            v.description = e.description();
            v.enginePrompt = e.designPrompt();
            v.defaultInstruction = e.instruction();
            v.sampleText = PreviewTexts.TEXTS.get("pl");
            if (v.referenceKey != null && !v.referenceKey.equals(e.reference())) resetReference(v);
            v.referenceKey = e.reference();

            byte[] prompt = e.reference() == null ? null : promptCache.computeIfAbsent(e.reference(), this::readPrompt);
            if (prompt != null && prompt.length > 0) {
                boolean same = v.referenceFile != null && storage.exists(v.referenceFile)
                        && Arrays.equals(storage.read(v.referenceFile), prompt);
                if (!same) {
                    resetReference(v);
                    v.referenceFile = storage.save("ref", prompt);
                    v.referenceText = e.referenceText();
                    imported++;
                }
            }
            if (isNew) v.createdAt = java.time.Instant.now();
            voices.save(v);
        }
        voices.flush();
        migrateLegacy();
        if (imported > 0) log.info("Wczytano {} nagrań wzorcowych (Master Prompt) z {}", imported, promptsDir);
    }

    /** Nowe nagranie wzorcowe = nowa barwa: referencja w silniku i próbki do odtworzenia od nowa. */
    private void resetReference(Voice v) {
        v.referenceId = null;
        v.referenceFile = null;
        v.referenceText = null;
        v.sampleFile = null;
        v.sampleEngineMode = null;
        if (v.id != null) previews.deleteByVoiceId(v.id);
    }

    private byte[] readPrompt(String relative) {
        Path f = promptsDir.resolve(relative).normalize();
        if (!f.startsWith(promptsDir) || !Files.isRegularFile(f)) return new byte[0];
        try {
            return Files.readAllBytes(f);
        } catch (IOException ex) {
            log.warn("Nie można odczytać nagrania wzorcowego {}: {}", f, ex.getMessage());
            return new byte[0];
        }
    }

    /** Przepina odwołania do głosów poprzedniej biblioteki i usuwa je. Idempotentne. */
    private void migrateLegacy() {
        for (var m : LEGACY.entrySet()) {
            String from = m.getKey(), to = m.getValue();
            Integer exists = jdbc.queryForObject("select count(*) from voices where id = ? and owner_id is null", Integer.class, from);
            if (exists == null || exists == 0) continue;
            jdbc.update("update projects set voice_id = ? where voice_id = ?", to, from);
            jdbc.update("update segments set voice_id = ? where voice_id = ?", to, from);
            jdbc.update("update accounts set default_voice_id = ? where default_voice_id = ?", to, from);
            jdbc.update("delete from voice_favorites f where f.voice_id = ? and exists "
                    + "(select 1 from voice_favorites g where g.user_id = f.user_id and g.voice_id = ?)", from, to);
            jdbc.update("update voice_favorites set voice_id = ? where voice_id = ?", to, from);
            jdbc.update("delete from voice_previews where voice_id = ?", from);
            jdbc.update("delete from voices where id = ? and owner_id is null", from);
            log.info("Głos {} zastąpiony głosem {} z nowego katalogu", from, to);
        }
    }
}
