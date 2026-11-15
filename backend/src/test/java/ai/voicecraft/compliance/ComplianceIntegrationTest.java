package ai.voicecraft.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.audio.AiContentLabel;
import ai.voicecraft.audio.AudioStorage;
import ai.voicecraft.audio.WavUtils;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos;
import ai.voicecraft.history.GenerationRecord;
import ai.voicecraft.history.HistoryRepository;
import ai.voicecraft.maintenance.CleanupService;
import ai.voicecraft.voice.ConsentTexts;
import ai.voicecraft.voice.VoiceRepository;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Licznik zużycia, nagrania wzorcowe głosów, oznaczanie treści AI, rejestr zgód, eksport danych i sprzątanie —
 * z atrapą silnika VoxCPM2 (z obsługą nagrań referencyjnych).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ComplianceIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    VoiceRepository voices;

    @Autowired
    AudioStorage storage;

    @Autowired
    HistoryRepository history;

    @Autowired
    CleanupService cleanup;

    @MockitoBean
    EngineClient engine;

    final List<EngineDtos.SynthesizeRequest> calls = new ArrayList<>();

    static byte[] tone(double seconds) {
        float[] x = new float[(int) (24000 * seconds)];
        for (int i = 0; i < x.length; i++) x[i] = (float) (0.2 * Math.sin(2 * Math.PI * 220 * i / 24000.0));
        return WavUtils.encode(x, 24000);
    }

    @BeforeEach
    void engineStub() {
        var caps = new EngineDtos.Capabilities(true, true, true, true, false, List.of("pl"));
        var health = new EngineDtos.Health("ok", "voxcpm2", "openbmb/VoxCPM2", "cuda", 48000, caps, null);
        when(engine.health(anyBoolean())).thenReturn(health);
        when(engine.requireOnline()).thenReturn(health);
        when(engine.design(any())).thenAnswer(inv -> new EngineDtos.Audio(tone(3), "voxcpm2", 3000));
        when(engine.createReference(any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenAnswer(inv -> new EngineDtos.CloneReference(inv.getArgument(4), 3.0));
        when(engine.createReference(any(), any(), any(), anyBoolean()))
                .thenReturn(new EngineDtos.CloneReference("ref_0123456789ab", 12.0));
        when(engine.synthesize(any())).thenAnswer(inv -> {
            calls.add(inv.getArgument(0));
            return new EngineDtos.Audio(tone(1), "voxcpm2", 1000);
        });
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t").content(body);
    }

    private Cookie proUser(String email) throws Exception {
        Cookie c = mvc.perform(json(post("/api/auth/register"),
                        "{\"name\":\"T\",\"email\":\"" + email + "\",\"password\":\"Haslo12345\",\"acceptTerms\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("vc_session");
        mvc.perform(json(put("/api/account/plan").cookie(c), "{\"planId\":\"PRO\"}")).andExpect(status().isOk());
        return c;
    }

    private String project(Cookie c, String voiceId, String text) throws Exception {
        String body = mvc.perform(json(post("/api/projects").cookie(c), "{\"name\":\"Zgodność\"}")).andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(json(put("/api/projects/" + id + "/segments").cookie(c),
                "[{\"id\":\"s1\",\"text\":\"" + text + "\",\"voiceId\":\"" + voiceId + "\"}]")).andExpect(status().isOk());
        return id;
    }

    private void generate(Cookie c, String pid) throws Exception {
        mvc.perform(json(post("/api/projects/" + pid + "/segments/s1/generate").cookie(c), "")).andExpect(status().isOk());
    }

    private Map<String, Object> usage(Cookie c) throws Exception {
        return JsonParserFactory.getJsonParser().parseMap(mvc.perform(get("/api/account/usage").cookie(c))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void anchorVoiceUsageSurvivesHistoryClearAndExportIsLabeled() throws Exception {
        Cookie c = proUser("anchor@test.pl");
        String pid = project(c, "pl-zofia", "Pierwsze zdanie nagrania.");

        generate(c, pid);
        generate(c, pid);

        // Nagranie wzorcowe powstaje raz (stały seed), potem każda synteza idzie przez referencję
        verify(engine, times(1)).design(argThat(r -> r.params().seed() != null));
        verify(engine, times(1)).createReference(any(), any(), any(), eq(true), eq("anchor-pl-zofia"), eq(true));
        assertThat(calls).hasSize(2).allSatisfy(r -> {
            assertThat(r.voice().referenceId()).isEqualTo("anchor-pl-zofia");
            assertThat(r.voice().description()).isNull();
        });
        var v = voices.findById("pl-zofia").orElseThrow();
        assertThat(v.referenceFile).isNotNull();
        assertThat(storage.exists(v.referenceFile)).isTrue();
        assertThat(v.sampleFile).isEqualTo(v.referenceFile); // próbka w bibliotece = nagranie wzorcowe

        // Wyczyszczenie historii nie zeruje licznika zużycia
        Object used = usage(c).get("charactersUsed");
        assertThat(((Number) used).intValue()).isEqualTo(2 * "Pierwsze zdanie nagrania.".length());
        mvc.perform(delete("/api/history").cookie(c).header("X-Requested-With", "t")).andExpect(status().isOk());
        assertThat(usage(c).get("charactersUsed")).isEqualTo(used);

        // Silnik utracił referencję (nowy kontener) → ponowna rejestracja z kopii i powtórzenie syntezy
        when(engine.synthesize(any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "REFERENCE_MISSING:anchor-pl-zofia"))
                .thenAnswer(inv -> new EngineDtos.Audio(tone(1), "voxcpm2", 1000));
        generate(c, pid);
        verify(engine, times(2)).createReference(any(), any(), any(), eq(true), eq("anchor-pl-zofia"), eq(true));
        verify(engine, times(1)).design(any());

        // Eksport WAV oznaczony jako treść AI
        MvcResult exp = mvc.perform(get("/api/projects/" + pid + "/export?format=wav").cookie(c))
                .andExpect(status().isOk()).andExpect(header().string("X-AI-Generated", "true")).andReturn();
        var info = AiContentLabel.readWavInfo(exp.getResponse().getContentAsByteArray());
        assertThat(info.get("ICMT")).contains("AI-generated").contains("VoxCPM2");
        assertThat(info.get("INAM")).isEqualTo("Zgodność");
    }

    @Test
    void cloningConsentIsRecordedAndDataExportContainsEverything() throws Exception {
        Cookie c = proUser("clone@test.pl");
        var consent = JsonParserFactory.getJsonParser().parseMap(
                mvc.perform(get("/api/voices/clone/consent").cookie(c)).andReturn().getResponse().getContentAsString());
        assertThat(consent.get("version")).isEqualTo(ConsentTexts.CLONING_VERSION);

        byte[] recording = tone(12);
        var file = new MockMultipartFile("file", "moj-glos.wav", "audio/wav", recording);
        // Nieaktualna treść oświadczenia → odrzucenie
        mvc.perform(multipart("/api/voices/clone").file(file).cookie(c).header("X-Requested-With", "t")
                        .param("name", "Mój głos").param("consent", "true").param("consentVersion", "stara"))
                .andExpect(status().isConflict());
        String body = mvc.perform(multipart("/api/voices/clone").file(file).cookie(c).header("X-Requested-With", "t")
                        .header("User-Agent", "JUnit").param("name", "Mój głos").param("transcript", "To jest mój głos.")
                        .param("consent", "true").param("consentVersion", ConsentTexts.CLONING_VERSION))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String voiceId = body.replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
        assertThat(body).contains("\"anchored\":true").doesNotContain("referenceFile");

        var saved = JsonParserFactory.getJsonParser().parseMap(
                mvc.perform(get("/api/voices/" + voiceId + "/consent").cookie(c)).andReturn().getResponse().getContentAsString());
        assertThat(saved.get("ip")).isEqualTo("127.0.0.1");
        assertThat(saved.get("fileName")).isEqualTo("moj-glos.wav");
        assertThat(saved.get("audioSha256")).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(recording)));
        // Kopia nagrania w magazynie aplikacji (odtworzenie referencji po restarcie silnika)
        var v = voices.findById(voiceId).orElseThrow();
        assertThat(storage.read(v.referenceFile)).isEqualTo(recording);
        assertThat(v.referenceText).isEqualTo("To jest mój głos.");
        // Zgoda nie jest widoczna dla innego użytkownika
        Cookie other = proUser("other@test.pl");
        mvc.perform(get("/api/voices/" + voiceId + "/consent").cookie(other)).andExpect(status().isNotFound());

        // Eksport danych (RODO): archiwum ZIP z danymi i plikami audio
        String pid = project(c, voiceId, "Fragment moim głosem.");
        generate(c, pid);
        MvcResult async = mvc.perform(get("/api/account/export").cookie(c)).andExpect(request().asyncStarted()).andReturn();
        byte[] zip = mvc.perform(asyncDispatch(async)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip")).andReturn().getResponse().getContentAsByteArray();
        Map<String, String> entries = new HashMap<>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.put(e.getName(), new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }
        assertThat(entries).containsKeys("CZYTAJ.txt", "konto.json", "projekty.json", "glosy.json", "historia.json",
                "zuzycie.json", "slownik_wymowy.json", "zgody_klonowanie.json",
                "audio/glosy/" + voiceId + "_nagranie_wzorcowe.wav", "audio/projekty/" + pid + "/001_s1.wav");
        assertThat(entries.get("konto.json")).contains("clone@test.pl").doesNotContain("passwordHash").doesNotContain("$2a$");
        assertThat(entries.get("zgody_klonowanie.json")).contains(ConsentTexts.CLONING_VERSION);
    }

    @Test
    void cleanupRemovesExpiredHistoryAndOrphanedFilesOnly() throws Exception {
        Cookie c = proUser("cleanup@test.pl");
        String pid = project(c, "pl-anna", "Nagranie, które zostaje.");
        generate(c, pid);
        String uid = JsonParserFactory.getJsonParser().parseMap(mvc.perform(get("/api/auth/me").cookie(c))
                .andReturn().getResponse().getContentAsString()).get("id").toString();
        String current = history.findAllByUserIdOrderByCreatedAtDesc(uid).getFirst().audioFile;

        String orphan = storage.save("seg", tone(0.1));
        var old = new GenerationRecord();
        old.userId = uid;
        old.type = "segment";
        old.status = "success";
        old.audioFile = storage.save("seg", tone(0.1));
        old.createdAt = Instant.now().minus(Duration.ofDays(120)); // PRO przechowuje 90 dni
        history.save(old);

        // Pusty projekt (bez tekstu) zostaje usunięty, projekt z tekstem — nie
        String empty = mvc.perform(json(post("/api/projects").cookie(c), "{\"name\":\"Pusty\"}")).andReturn().getResponse()
                .getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");

        var report = cleanup.run(Instant.now().plus(Duration.ofDays(2)));
        assertThat(report.emptyProjectsRemoved()).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/projects/" + empty).cookie(c)).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + pid).cookie(c)).andExpect(status().isOk());
        assertThat(report.historyRemoved()).isGreaterThanOrEqualTo(1);
        assertThat(report.filesRemoved()).isGreaterThanOrEqualTo(2);
        assertThat(storage.exists(orphan)).isFalse();
        assertThat(storage.exists(old.audioFile)).isFalse();
        assertThat(history.findById(old.id)).isEmpty();
        assertThat(storage.exists(current)).isTrue();
        mvc.perform(get("/api/projects/" + pid + "/segments/s1/audio").cookie(c)).andExpect(status().isOk());
        // Plik zapisany przed chwilą nie jest usuwany (zapas 24 h)
        String fresh = storage.save("seg", tone(0.1));
        cleanup.run(Instant.now());
        assertThat(storage.exists(fresh)).isTrue();
    }
}
