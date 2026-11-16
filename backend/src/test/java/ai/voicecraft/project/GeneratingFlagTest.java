package ai.voicecraft.project;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.audio.WavUtils;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Lista projektów zgłasza trwające generowanie — dzięki temu spinner w „Ostatnich projektach” przetrwa odświeżenie strony. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeneratingFlagTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    EngineClient engine;

    @Test
    void projectListReportsGenerationUntilJobFinishes() throws Exception {
        var caps = new EngineDtos.Capabilities(true, true, true, true, false, List.of("pl"));
        var health = new EngineDtos.Health("ok", "voxcpm2", "openbmb/VoxCPM2", "cuda", 48000, caps, null);
        when(engine.health(anyBoolean())).thenReturn(health);
        when(engine.requireOnline()).thenReturn(health);
        byte[] wav = WavUtils.encode(new float[24000], 24000);
        when(engine.design(any())).thenReturn(new EngineDtos.Audio(wav, "voxcpm2", 1000));
        when(engine.createReference(any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenAnswer(inv -> new EngineDtos.CloneReference(inv.getArgument(4), 1.0));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(engine.synthesize(any())).thenAnswer(inv -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new EngineDtos.Audio(wav, "voxcpm2", 1000);
        });

        Cookie c = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t")
                        .content("{\"name\":\"G\",\"email\":\"gen-flag@test.pl\",\"password\":\"Haslo12345\",\"acceptTerms\":true}"))
                .andReturn().getResponse().getCookie("vc_session");
        String body = mvc.perform(post("/api/projects").cookie(c).header("X-Requested-With", "t").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Spinner\",\"text\":\"Pierwszy akapit.\\n\\nDrugi akapit.\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/projects").cookie(c)).andExpect(jsonPath("$[?(@.id=='" + id + "')].generating").value(false));
        mvc.perform(post("/api/projects/" + id + "/jobs").cookie(c).header("X-Requested-With", "t")).andExpect(status().isOk());
        started.await(10, TimeUnit.SECONDS);
        mvc.perform(get("/api/projects").cookie(c)).andExpect(jsonPath("$[?(@.id=='" + id + "')].generating").value(true));

        release.countDown();
        boolean done = false;
        for (int i = 0; i < 100 && !done; i++) {
            String json = mvc.perform(get("/api/projects").cookie(c)).andReturn().getResponse().getContentAsString();
            int at = json.indexOf("\"id\":\"" + id + "\"");
            done = json.startsWith("\"generating\":false", json.indexOf("\"generating\":", at));
            if (!done) Thread.sleep(50);
        }
        mvc.perform(get("/api/projects").cookie(c)).andExpect(jsonPath("$[?(@.id=='" + id + "')].generating").value(false))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].stats.status").value("ready"));
    }
}
