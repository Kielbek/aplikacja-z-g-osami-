package ai.voicecraft.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.account.SubscriptionPlan;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos;
import ai.voicecraft.project.ProjectRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Limit miejsca na nagrania zamiast liczby projektów; nagrania w planie Free wygasają po roku. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StorageQuotaTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    StoredFileRepository files;

    @Autowired
    ProjectRepository projects;

    @Autowired
    AudioStorage storage;

    @MockitoBean
    EngineClient engine;

    private void stub() {
        var caps = new EngineDtos.Capabilities(true, true, true, true, false, List.of("pl"));
        var health = new EngineDtos.Health("ok", "voxcpm2", "openbmb/VoxCPM2", "cuda", 48000, caps, null);
        when(engine.health(anyBoolean())).thenReturn(health);
        when(engine.requireOnline()).thenReturn(health);
        byte[] wav = WavUtils.encode(new float[24000], 24000);
        when(engine.design(any())).thenReturn(new EngineDtos.Audio(wav, "voxcpm2", 1000));
        when(engine.createReference(any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenAnswer(inv -> new EngineDtos.CloneReference(inv.getArgument(4), 1.0));
        when(engine.synthesize(any())).thenReturn(new EngineDtos.Audio(wav, "voxcpm2", 1000));
    }

    @Test
    void generatedAudioCountsTowardsStorageAndFullStorageBlocksGeneration() throws Exception {
        stub();
        var reg = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t")
                .content("{\"name\":\"S\",\"email\":\"storage@test.pl\",\"password\":\"Haslo12345\",\"acceptTerms\":true}")).andReturn();
        Cookie c = reg.getResponse().getCookie("vc_session");
        String uid = reg.getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");

        // Wiele projektów jest dozwolonych (limitem jest miejsce, nie liczba projektów)
        String id = "";
        for (int i = 0; i < 5; i++) {
            id = mvc.perform(post("/api/projects").cookie(c).header("X-Requested-With", "t").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"Krótki tekst numer " + i + ".\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString().replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");
        }
        String seg = mvc.perform(get("/api/projects/" + id).cookie(c)).andReturn().getResponse().getContentAsString()
                .replaceAll("(?s).*?\"segments\":\\[\\{\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/projects/" + id + "/segments/" + seg + "/generate").cookie(c).header("X-Requested-With", "t"))
                .andExpect(status().isOk());
        long used = files.sumBytes(uid);
        assertThat(used).isGreaterThan(40_000); // 1 s WAV 24 kHz
        mvc.perform(get("/api/account/usage").cookie(c))
                .andExpect(jsonPath("$.storageUsedBytes").value(used))
                .andExpect(jsonPath("$.storageLimitBytes").value(SubscriptionPlan.FREE.storageBytes()));

        // Pełne miejsce: kolejne nagranie nie powstaje (402 STORAGE_FULL z wartościami dla interfejsu)
        files.save(StoredFile.of("seg_ffffffffffffffff.wav", uid, SubscriptionPlan.FREE.storageBytes()));
        mvc.perform(post("/api/projects/" + id + "/segments/" + seg + "/generate").cookie(c).header("X-Requested-With", "t"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("STORAGE_FULL"))
                .andExpect(jsonPath("$.details.limitBytes").value(SubscriptionPlan.FREE.storageBytes()));
        files.deleteByKey("seg_ffffffffffffffff.wav");

        // Usunięcie pliku zwalnia miejsce
        String key = storage.saveFor(uid, "seg", WavUtils.encode(new float[2400], 24000));
        assertThat(files.sumBytes(uid)).isGreaterThan(used);
        storage.delete(key);
        assertThat(files.sumBytes(uid)).isEqualTo(used);

        // Plan Free: nagrania starsze niż rok wygasają, tekst projektu zostaje
        int expired = projects.expireAudio(SubscriptionPlan.FREE, Instant.now().plus(Duration.ofDays(1)));
        assertThat(expired).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/projects/" + id).cookie(c))
                .andExpect(jsonPath("$.segments[0].text").value("Krótki tekst numer 4."))
                .andExpect(jsonPath("$.stats.readyCount").value(0));
    }
}
