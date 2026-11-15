package ai.voicecraft.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.audio.WavUtils;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Katalog 21 głosów w 5 kategoriach, próbki w 30 językach (plik statyczny / pamięć podręczna), natywny akcent. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoicePreviewIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    VoicePreviewService previews;

    @Autowired
    VoiceRepository voices;

    @MockitoBean
    EngineClient engine;

    final List<EngineDtos.SynthesizeRequest> calls = new ArrayList<>();

    @BeforeEach
    void stub() {
        var caps = new EngineDtos.Capabilities(true, true, true, true, false, List.of("pl", "it"));
        var health = new EngineDtos.Health("ok", "voxcpm2", "openbmb/VoxCPM2", "cuda", 48000, caps, null);
        when(engine.health(anyBoolean())).thenReturn(health);
        when(engine.requireOnline()).thenReturn(health);
        byte[] wav = WavUtils.encode(new float[24000], 24000);
        when(engine.design(any())).thenReturn(new EngineDtos.Audio(wav, "voxcpm2", 1000));
        when(engine.createReference(any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenAnswer(inv -> new EngineDtos.CloneReference(inv.getArgument(4), 1.0));
        when(engine.synthesize(any())).thenAnswer(inv -> {
            calls.add(inv.getArgument(0));
            return new EngineDtos.Audio(wav, "voxcpm2", 1000);
        });
    }

    private Cookie user(String email) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t")
                        .content("{\"name\":\"T\",\"email\":\"" + email + "\",\"password\":\"Haslo12345\",\"acceptTerms\":true}"))
                .andReturn().getResponse().getCookie("vc_session");
    }

    @Test
    void catalogHas21VoicesIn5CategoriesAnd30Languages() throws Exception {
        Cookie c = user("catalog@test.pl");
        mvc.perform(get("/api/voices/categories").cookie(c)).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].id").value("audiobooks"));
        mvc.perform(get("/api/voices/preview-languages").cookie(c)).andExpect(jsonPath("$.length()").value(30));
        mvc.perform(get("/api/voices?kind=library").cookie(c)).andExpect(jsonPath("$.length()").value(21));
        var relax = voices.findById("voice_relax_velvet").orElseThrow();
        assertThat(relax.referenceKey).isEqualTo(voices.findById("voice_warm_velvet").orElseThrow().referenceKey);
        assertThat(relax.languages).hasSize(30).startsWith("pl");
        assertThat(voices.findById("pl-anna")).isEmpty();
    }

    @Test
    void previewIsGeneratedOnceInTargetLanguageWithNativeAccentThenCached() throws Exception {
        Cookie c = user("preview@test.pl");
        calls.clear();
        mvc.perform(get("/api/voices/voice_relax_velvet/preview?lang=it").cookie(c))
                .andExpect(status().isOk()).andExpect(header().string("X-Preview-Source", "generated"));
        mvc.perform(get("/api/voices/voice_relax_velvet/preview?lang=it").cookie(c))
                .andExpect(status().isOk()).andExpect(header().string("X-Preview-Source", "cache"));
        assertThat(calls).hasSize(1);
        var req = calls.getFirst();
        assertThat(req.language()).isEqualTo("it");
        assertThat(req.text()).isEqualTo(PreviewTexts.TEXTS.get("it"));
        assertThat(req.params().ultimateCloning()).isFalse(); // natywny akcent języka docelowego
        assertThat(req.voice().referenceId()).isNotNull();    // barwa z nagrania wzorcowego
        // Wariant „Relaks”: domyślna instrukcja sterująca, gdy projekt nie ustawia stylu ani emocji
        assertThat(req.params().style()).contains("calm");

        mvc.perform(get("/api/voices/voice_relax_velvet/preview?lang=xx").cookie(c)).andExpect(status().isBadRequest());
    }

    @Test
    void staticSampleIsServedWithoutCallingTheEngine() throws Exception {
        Cookie c = user("static@test.pl");
        var dir = previews.samplesDir().resolve("voice_deep_trailer");
        Files.createDirectories(dir);
        byte[] wav = WavUtils.encode(new float[4800], 24000);
        Files.write(dir.resolve("ja.wav"), wav);
        calls.clear();
        byte[] body = mvc.perform(get("/api/voices/voice_deep_trailer/preview?lang=ja").cookie(c))
                .andExpect(status().isOk()).andExpect(header().string("X-Preview-Source", "static"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(wav);
        assertThat(calls).isEmpty();
    }
}
