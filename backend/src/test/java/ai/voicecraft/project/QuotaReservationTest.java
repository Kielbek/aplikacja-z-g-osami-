package ai.voicecraft.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.account.AccountService;
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

/**
 * Limit znaków sprawdzany dla całego tekstu przed startem generowania i rezerwowany do końca zadania —
 * generowanie nie urywa się w połowie, a nowe teksty nie „podbierają” znaków trwającym zadaniom.
 * Do tego automatyczne nazwy projektów.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotaReservationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService accounts;

    @MockitoBean
    EngineClient engine;

    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch started = new CountDownLatch(1);

    private void stub(boolean block) {
        var caps = new EngineDtos.Capabilities(true, true, true, true, false, List.of("pl"));
        var health = new EngineDtos.Health("ok", "voxcpm2", "openbmb/VoxCPM2", "cuda", 48000, caps, null);
        when(engine.health(anyBoolean())).thenReturn(health);
        when(engine.requireOnline()).thenReturn(health);
        byte[] wav = WavUtils.encode(new float[24000], 24000);
        when(engine.design(any())).thenReturn(new EngineDtos.Audio(wav, "voxcpm2", 1000));
        when(engine.createReference(any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenAnswer(inv -> new EngineDtos.CloneReference(inv.getArgument(4), 1.0));
        when(engine.synthesize(any())).thenAnswer(inv -> {
            started.countDown();
            if (block) release.await(10, TimeUnit.SECONDS);
            return new EngineDtos.Audio(wav, "voxcpm2", 1000);
        });
    }

    private record U(String id, Cookie c) {}

    private U user(String email) throws Exception {
        var r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t")
                .content("{\"name\":\"Q\",\"email\":\"" + email + "\",\"password\":\"Haslo12345\",\"acceptTerms\":true}")).andReturn();
        return new U(r.getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1"), r.getResponse().getCookie("vc_session"));
    }

    /** Projekt z tekstem o podanej liczbie akapitów po ~390 znaków; zwraca [id, liczba znaków]. */
    private String[] project(U u, int paragraphs, String name) throws Exception {
        String para = "Ala ma kota, a kot ma Alę. ".repeat(14).trim();
        String text = String.join("\\n\\n", java.util.Collections.nCopies(paragraphs, para));
        String nameJson = name == null ? "" : "\"name\":\"" + name + "\",";
        String body = mvc.perform(post("/api/projects").cookie(u.c).header("X-Requested-With", "t").contentType(MediaType.APPLICATION_JSON)
                .content("{" + nameJson + "\"text\":\"" + text + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new String[] {body.replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1"), body.replaceAll("(?s).*\"characters\":(\\d+).*", "$1")};
    }

    @Test
    void wholeTextMustFitBeforeStartAndRunningJobsReserveTheirCharacters() throws Exception {
        stub(true);
        U u = user("reserve@test.pl"); // plan Free: 10 000 znaków
        String[] a = project(u, 10, null);
        int aChars = Integer.parseInt(a[1]);
        accounts.recordUsage(u.id, "segment", 10_000 - aChars - 1_000, 1000); // zostaje aChars + 1 000

        mvc.perform(post("/api/projects/" + a[0] + "/jobs").cookie(u.c).header("X-Requested-With", "t")).andExpect(status().isOk());
        started.await(5, TimeUnit.SECONDS);
        mvc.perform(get("/api/account/usage").cookie(u.c)).andExpect(jsonPath("$.reservedCharacters").value(org.hamcrest.Matchers.greaterThan(0)));

        // Drugi tekst (ok. 3 900 znaków) nie zmieści się w pozostałym 1 000 — odmowa od razu, z liczbami dla okna z ofertą
        String[] b = project(u, 10, "Drugi tekst");
        mvc.perform(post("/api/projects/" + b[0] + "/jobs").cookie(u.c).header("X-Requested-With", "t"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.details.needed").value(Integer.parseInt(b[1])))
                .andExpect(jsonPath("$.details.available").value(org.hamcrest.Matchers.lessThanOrEqualTo(1_000)));
        String segB = mvc.perform(get("/api/projects/" + b[0]).cookie(u.c)).andReturn().getResponse().getContentAsString()
                .replaceAll("(?s).*?\"segments\":\\[\\{\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/projects/" + b[0] + "/segments/" + segB + "/generate").cookie(u.c).header("X-Requested-With", "t"))
                .andExpect(status().isOk()); // jeden fragment (~390 znaków) mieści się w wolnym 1 000

        release.countDown();
        // Pierwsze zadanie dochodzi do końca mimo kolejnych żądań — nie zostaje zablokowane w połowie
        String status = "";
        for (int i = 0; i < 100 && !status.contains("\"status\":\"DONE\""); i++) {
            Thread.sleep(50);
            status = mvc.perform(get("/api/projects/" + a[0] + "/jobs/active").cookie(u.c)).andReturn().getResponse().getContentAsString();
            if (status.isBlank()) break;
        }
        mvc.perform(get("/api/projects/" + a[0]).cookie(u.c)).andExpect(jsonPath("$.stats.status").value("ready"));
        mvc.perform(get("/api/account/usage").cookie(u.c)).andExpect(jsonPath("$.reservedCharacters").value(0));
    }

    @Test
    void jobThatDoesNotFitIsRejectedWithoutTouchingTheEngine() throws Exception {
        stub(false);
        U u = user("nofit@test.pl");
        String[] p = project(u, 10, null);
        accounts.recordUsage(u.id, "segment", 9_000, 1000);
        clearInvocations(engine);
        mvc.perform(post("/api/projects/" + p[0] + "/jobs").cookie(u.c).header("X-Requested-With", "t"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.details.available").value(1_000));
        verify(engine, never()).synthesize(any());
    }

    @Test
    void projectsGetAutomaticNamesUntilRenamed() throws Exception {
        stub(false);
        U u = user("names@test.pl");
        String id = mvc.perform(post("/api/projects").cookie(u.c).header("X-Requested-With", "t").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.name").value("Projekt bez tytułu")).andReturn().getResponse().getContentAsString()
                .replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");
        String seg = "{\"id\":\"s1\",\"text\":\"Latarnik z Helu. Rozdział pierwszy.\",\"voiceId\":null}";
        mvc.perform(put("/api/projects/" + id + "/segments").cookie(u.c).header("X-Requested-With", "t").contentType(MediaType.APPLICATION_JSON)
                .content("[" + seg + "]")).andExpect(jsonPath("$.name").value("Latarnik z Helu"));
        mvc.perform(patch("/api/projects/" + id).cookie(u.c).header("X-Requested-With", "t").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Audiobook dla wydawnictwa\"}")).andExpect(jsonPath("$.name").value("Audiobook dla wydawnictwa"));
        mvc.perform(put("/api/projects/" + id + "/segments").cookie(u.c).header("X-Requested-With", "t").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"id\":\"s1\",\"text\":\"Zupełnie inny początek.\",\"voiceId\":null}]"))
                .andExpect(jsonPath("$.name").value("Audiobook dla wydawnictwa")); // własna nazwa się nie zmienia
        mvc.perform(patch("/api/projects/" + id).cookie(u.c).header("X-Requested-With", "t").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Projekt bez tytułu\"}")).andExpect(jsonPath("$.name").value("Zupełnie inny początek"));
        assertThat(id).isNotBlank();
    }
}
