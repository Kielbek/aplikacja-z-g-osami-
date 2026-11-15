package ai.voicecraft.editor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.audio.Loudness;
import ai.voicecraft.audio.WavUtils;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Głos per fragment, pauzy, słownik wymowy, wersje, napisy, normalizacja i import — z atrapą silnika TTS. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EditorFeaturesIntegrationTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    EngineClient engine;

    final List<EngineDtos.SynthesizeRequest> calls = new ArrayList<>();

    @BeforeEach
    void engineStub() {
        var caps = new EngineDtos.Capabilities(true, false, true, true, true, List.of("pl"));
        when(engine.health(any(Boolean.class))).thenReturn(new EngineDtos.Health("ok", "demo", null, null, 24000, caps, null));
        when(engine.requireOnline()).thenReturn(new EngineDtos.Health("ok", "demo", null, null, 24000, caps, null));
        when(engine.synthesize(any())).thenAnswer(inv -> {
            EngineDtos.SynthesizeRequest req = inv.getArgument(0);
            calls.add(req);
            // 1 s sygnału na każde 20 znaków, cichy (−30 dBFS) — do testu normalizacji
            int samples = 24000 * Math.max(1, req.text().length() / 20);
            float[] x = new float[samples];
            for (int i = 0; i < x.length; i++) x[i] = (float) (0.03 * Math.sin(2 * Math.PI * 440 * i / 24000.0));
            byte[] wav = WavUtils.encode(x, 24000);
            return new EngineDtos.Audio(wav, "demo", WavUtils.durationMs(wav));
        });
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t").content(body);
    }

    private Cookie user(String email) throws Exception {
        return mvc.perform(json(post("/api/auth/register"), "{\"name\":\"T\",\"email\":\"" + email + "\",\"password\":\"Haslo12345\",\"acceptTerms\":true}"))
                .andReturn().getResponse().getCookie("vc_session");
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> project(Cookie c, String id) throws Exception {
        return JsonParserFactory.getJsonParser().parseMap(mvc.perform(get("/api/projects/" + id).cookie(c)).andReturn().getResponse().getContentAsString());
    }

    @SuppressWarnings("unchecked")
    private List<java.util.Map<String, Object>> segments(Cookie c, String id) throws Exception {
        return (List<java.util.Map<String, Object>>) project(c, id).get("segments");
    }

    private String createProject(Cookie c) throws Exception {
        mvc.perform(json(put("/api/account/plan").cookie(c), "{\"planId\":\"PRO\"}")).andExpect(status().isOk());
        String body = mvc.perform(json(post("/api/projects").cookie(c), "{\"name\":\"Dialog\"}")).andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(json(put("/api/projects/" + id + "/segments").cookie(c),
                "[{\"id\":\"s1\",\"text\":\"Dzień dobry, tu Anna. [pauza 1s] Zaczynamy rozmowę o VoxCPM.\"},"
                        + "{\"id\":\"s2\",\"text\":\"A ja jestem Tomasz i mówię innym głosem.\",\"voiceId\":\"voice_warm_narrator\"}]"))
                .andExpect(status().isOk());
        return id;
    }

    private void generate(Cookie c, String pid, String sid) throws Exception {
        mvc.perform(json(post("/api/projects/" + pid + "/segments/" + sid + "/generate").cookie(c), "")).andExpect(status().isOk());
    }

    @Test
    void perSegmentVoicePausesAndDictionary() throws Exception {
        Cookie c = user("editor1@test.pl");
        String pid = createProject(c);
        generate(c, pid, "s1");
        generate(c, pid, "s2");

        // Pauza: dwa wywołania silnika dla s1 (tekst przed i po znaczniku), s2 — głos fragmentu
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).voice().id()).isEqualTo("voice_warm_velvet");
        assertThat(calls.get(2).voice().id()).isEqualTo("voice_warm_narrator");
        var segs = segments(c, pid);
        // ~1 s + 1 s pauzy + ~1 s
        assertThat(((Number) segs.get(0).get("durationMs")).longValue()).isBetween(2900L, 3100L);
        assertThat(segs.get(1).get("voiceId")).isEqualTo("voice_warm_narrator");
        assertThat(segs).extracting(m -> m.get("status")).containsOnly("ready");

        // Słownik: hasło występuje tylko w s1 → tylko s1 staje się nieaktualny
        mvc.perform(json(post("/api/pronunciation").cookie(c), "{\"projectId\":\"" + pid + "\",\"term\":\"VoxCPM\",\"replacement\":\"woks ce pe em\"}"))
                .andExpect(status().isOk());
        assertThat(segments(c, pid)).extracting(m -> m.get("status")).containsExactly("stale", "ready");
        generate(c, pid, "s1");
        assertThat(calls.getLast().text()).isEqualTo("Zaczynamy rozmowę o woks ce pe em.");

        // Zmiana głosu fragmentu s2 → tylko s2 nieaktualny
        mvc.perform(json(put("/api/projects/" + pid + "/segments").cookie(c),
                "[{\"id\":\"s1\",\"text\":\"Dzień dobry, tu Anna. [pauza 1s] Zaczynamy rozmowę o VoxCPM.\"},"
                        + "{\"id\":\"s2\",\"text\":\"A ja jestem Tomasz i mówię innym głosem.\",\"voiceId\":\"pl-marek\"}]"));
        assertThat(segments(c, pid)).extracting(m -> m.get("status")).containsExactly("ready", "stale");
    }

    @Test
    void versionsCanBeRestored() throws Exception {
        Cookie c = user("editor2@test.pl");
        String pid = createProject(c);
        generate(c, pid, "s2");
        String firstAudio = (String) segments(c, pid).get(1).get("audioVersion");
        generate(c, pid, "s2");
        String body = mvc.perform(get("/api/projects/" + pid + "/segments/s2/versions").cookie(c))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].current").value(true))
                .andReturn().getResponse().getContentAsString();
        String olderId = body.replaceAll("(?s).*\"id\":(\\d+).*", "$1");
        mvc.perform(json(post("/api/projects/" + pid + "/segments/s2/versions/" + olderId + "/restore").cookie(c), ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ready"));
        assertThat(segments(c, pid).get(1).get("audioVersion")).isEqualTo(firstAudio);
        mvc.perform(get("/api/projects/" + pid + "/segments/s2/versions/" + olderId + "/audio").cookie(c)).andExpect(status().isOk());
        // Cudza wersja jest niedostępna
        Cookie other = user("editor2b@test.pl");
        mvc.perform(get("/api/projects/" + pid + "/segments/s2/versions").cookie(other)).andExpect(status().isNotFound());
    }

    @Test
    void subtitlesAndLoudnessNormalizedExport() throws Exception {
        Cookie c = user("editor3@test.pl");
        String pid = createProject(c);
        mvc.perform(get("/api/projects/" + pid + "/subtitles").cookie(c)).andExpect(status().isConflict());
        generate(c, pid, "s1");
        generate(c, pid, "s2");
        String srt = mvc.perform(get("/api/projects/" + pid + "/subtitles?format=srt").cookie(c))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(srt).startsWith("1\n00:00:00,000 --> ").contains("Zaczynamy rozmowę o VoxCPM.").doesNotContain("[pauza");

        var res = mvc.perform(get("/api/projects/" + pid + "/export?format=wav").cookie(c)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getHeader("X-Loudness-LUFS")).isEqualTo("-16.0");
        var pcm = WavUtils.decode(res.getContentAsByteArray());
        assertThat(Loudness.integrated(pcm.samples(), pcm.format().getSampleRate())).isBetween(-16.3, -15.7);
    }

    @Test
    void importCreatesSegmentsFromFile() throws Exception {
        Cookie c = user("editor4@test.pl");
        var file = new MockMultipartFile("file", "Rozdział 1.txt", "text/plain",
                "Pierwszy akapit.\n\nDrugi akapit, nieco dłuższy.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/import/text").file(file).cookie(c).header("X-Requested-With", "t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Rozdział 1"))
                .andExpect(jsonPath("$.segments.length()").value(2));
    }
}
