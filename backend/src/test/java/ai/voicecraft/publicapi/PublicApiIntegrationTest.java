package ai.voicecraft.publicapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.account.AccountService;
import ai.voicecraft.audio.AiContentLabel;
import ai.voicecraft.audio.WavUtils;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Publiczne API: klucze, plan, limity, synteza, zadania w tle z idempotencją i podpisanym webhookiem. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicApiIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService accounts;

    @Autowired
    ApiRateLimiter limiter;

    @MockitoBean
    EngineClient engine;

    @BeforeEach
    void stub() {
        limiter.reset();
        var caps = new EngineDtos.Capabilities(true, true, true, true, false, List.of("pl", "en"));
        var health = new EngineDtos.Health("ok", "voxcpm2", "openbmb/VoxCPM2", "cuda", 48000, caps, null);
        when(engine.health(anyBoolean())).thenReturn(health);
        when(engine.requireOnline()).thenReturn(health);
        byte[] wav = WavUtils.encode(new float[24000], 24000);
        when(engine.design(any())).thenReturn(new EngineDtos.Audio(wav, "voxcpm2", 1000));
        when(engine.createReference(any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenAnswer(inv -> new EngineDtos.CloneReference(inv.getArgument(4), 1.0));
        when(engine.synthesize(any())).thenReturn(new EngineDtos.Audio(wav, "voxcpm2", 1000));
        when(engine.toMp3(any(), anyInt())).thenReturn(new byte[] {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00});
    }

    private record User(String id, Cookie cookie) {}

    private User user(String email, String plan) throws Exception {
        var r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t")
                .content("{\"name\":\"Dev\",\"email\":\"" + email + "\",\"password\":\"Haslo12345\",\"acceptTerms\":true}")).andReturn();
        var u = new User(r.getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1"),
                r.getResponse().getCookie("vc_session"));
        mvc.perform(put("/api/account/plan").cookie(u.cookie).header("X-Requested-With", "t")
                .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"" + plan + "\"}")).andExpect(status().isOk());
        return u;
    }

    private String newKey(User u) throws Exception {
        String body = mvc.perform(post("/api/api-keys").cookie(u.cookie).header("X-Requested-With", "t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Serwer produkcyjny\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions tts(String key, String json) throws Exception {
        return mvc.perform(post("/v1/text-to-speech").header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void keysAuthenticateOnlyOnPlansWithApiAccess() throws Exception {
        User free = user("api-free@test.pl", "FREE");
        mvc.perform(post("/api/api-keys").cookie(free.cookie).header("X-Requested-With", "t"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_required"));

        User pro = user("api-pro@test.pl", "PRO");
        String key = newKey(pro);
        assertThat(key).startsWith("nrv_live_").hasSizeGreaterThan(40);
        // Lista kluczy pokazuje tylko początek klucza
        String list = mvc.perform(get("/api/api-keys").cookie(pro.cookie)).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.keys[0].name").value("Serwer produkcyjny"))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(key);

        mvc.perform(get("/v1/usage")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("missing_api_key"))
                .andExpect(jsonPath("$.error.request_id").exists());
        mvc.perform(get("/v1/usage").cookie(pro.cookie)).andExpect(status().isUnauthorized()); // sesja przeglądarki nie wystarczy
        mvc.perform(get("/v1/usage").header("Authorization", "Bearer nrv_live_zly")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
        mvc.perform(get("/v1/usage").header("Authorization", "Bearer " + key)).andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "60"))
                .andExpect(jsonPath("$.plan").value("pro"))
                .andExpect(jsonPath("$.rate_limits.concurrent_requests").value(2));
        mvc.perform(get("/v1/usage").header("X-API-Key", key)).andExpect(status().isOk());
        mvc.perform(get("/v1/openapi.json")).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").value("3.1.0"));

        // Obniżenie planu odcina API, a klucz pozostaje (wróci po ponownym wykupieniu planu)
        mvc.perform(put("/api/account/plan").cookie(pro.cookie).header("X-Requested-With", "t")
                .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"STARTER\"}"));
        mvc.perform(get("/v1/usage").header("Authorization", "Bearer " + key)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("plan_required"));
        mvc.perform(put("/api/account/plan").cookie(pro.cookie).header("X-Requested-With", "t")
                .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"BUSINESS\"}"));
        mvc.perform(get("/v1/usage").header("Authorization", "Bearer " + key)).andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "300"));

        String id = mvc.perform(get("/api/api-keys").cookie(pro.cookie)).andReturn().getResponse().getContentAsString()
                .replaceAll("(?s).*\"keys\":\\[\\{\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(delete("/api/api-keys/" + id).cookie(pro.cookie).header("X-Requested-With", "t")).andExpect(status().isOk());
        mvc.perform(get("/v1/usage").header("Authorization", "Bearer " + key)).andExpect(status().isUnauthorized());
    }

    @Test
    void synchronousSpeechReturnsLabeledAudioAndChargesCharacters() throws Exception {
        User u = user("api-tts@test.pl", "PRO");
        String key = newKey(u);
        byte[] wav = tts(key, "{\"text\":\"Dzień dobry z API.\",\"voice_id\":\"voice_warm_velvet\",\"format\":\"wav\",\"emotion\":\"Happy\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"))
                .andExpect(header().string("X-Characters", "18"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(AiContentLabel.readWavInfo(wav).get("ISFT")).contains("Narrivy");
        tts(key, "{\"text\":\"MP3 też działa.\",\"voice_id\":\"voice_warm_velvet\"}")
                .andExpect(status().isOk()).andExpect(content().contentType("audio/mpeg"));

        mvc.perform(get("/v1/usage").header("Authorization", "Bearer " + key)).andExpect(jsonPath("$.characters_used").value(18 + 15));
        mvc.perform(get("/api/api-keys").cookie(u.cookie)).andExpect(jsonPath("$.charactersThisPeriod").value(33))
                .andExpect(jsonPath("$.keys[0].requestsThisPeriod").value(2));

        tts(key, "{\"text\":\"x\",\"voice_id\":\"nie_ma\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("voice_not_found"));
        tts(key, "{\"text\":\"x\",\"voiceId\":\"voice_warm_velvet\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("voiceId")));
        tts(key, "{\"text\":\"x\",\"voice_id\":\"voice_warm_velvet\",\"format\":\"ogg\"}").andExpect(status().isBadRequest());
        tts(key, "{\"text\":\"x\",\"voice_id\":\"voice_warm_velvet\",\"speed\":5}").andExpect(status().isBadRequest());
        tts(key, "{nie json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_json"));
        tts(key, "{\"text\":\"" + "a".repeat(5001) + "\",\"voice_id\":\"voice_warm_velvet\"}").andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("text_too_long"));

        // Wyczerpany limit planu (bez pakietów) → 402 quota_exceeded
        accounts.recordUsage(u.id, "segment", 200_000, 1000);
        tts(key, "{\"text\":\"Już nie.\",\"voice_id\":\"voice_warm_velvet\"}").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error.code").value("quota_exceeded"));
    }

    @Test
    void requestsPerMinuteAreLimited() throws Exception {
        String key = newKey(user("api-rate@test.pl", "PRO"));
        for (int i = 0; i < 60; i++) mvc.perform(get("/v1/voices").header("Authorization", "Bearer " + key)).andExpect(status().isOk());
        mvc.perform(get("/v1/voices").header("Authorization", "Bearer " + key))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));
    }

    @Test
    void voicesListLibraryAndOwnVoicesWithFilters() throws Exception {
        String key = newKey(user("api-voices@test.pl", "PRO"));
        mvc.perform(get("/v1/voices").header("Authorization", "Bearer " + key))
                .andExpect(jsonPath("$.object").value("list")).andExpect(jsonPath("$.data.length()").value(21))
                .andExpect(jsonPath("$.data[0].voice_id").exists());
        mvc.perform(get("/v1/voices?category=wellness_relax").header("Authorization", "Bearer " + key))
                .andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get("/v1/voices/voice_deep_trailer").header("Authorization", "Bearer " + key))
                .andExpect(jsonPath("$.name").value("Głęboki Bas")).andExpect(jsonPath("$.type").value("library"));
    }

    @Test
    void jobsAreIdempotentAndNotifySignedWebhook() throws Exception {
        User u = user("api-jobs@test.pl", "BUSINESS");
        String key = newKey(u);
        String secret = mvc.perform(get("/api/api-keys/webhook-secret").cookie(u.cookie)).andReturn().getResponse()
                .getContentAsString().replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        assertThat(secret).startsWith("whsec_");

        var received = new ArrayBlockingQueue<String[]>(5);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new String[] {ex.getRequestHeaders().getFirst("Narrivy-Signature"), body});
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            String hook = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            String text = "Pierwszy akapit dłuższego tekstu.\\n\\nDrugi akapit, który trafi do osobnej części.";
            String req = "{\"text\":\"" + text + "\",\"voice_id\":\"voice_warm_narrator\",\"format\":\"wav\",\"callback_url\":\"" + hook + "\"}";
            String created = mvc.perform(post("/v1/text-to-speech/jobs").header("Authorization", "Bearer " + key)
                            .header("Idempotency-Key", "zamowienie-42").contentType(MediaType.APPLICATION_JSON).content(req))
                    .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.object").value("text_to_speech_job"))
                    .andReturn().getResponse().getContentAsString();
            String id = created.replaceAll(".*\"id\":\"(job_[^\"]+)\".*", "$1");
            mvc.perform(post("/v1/text-to-speech/jobs").header("Authorization", "Bearer " + key)
                            .header("Idempotency-Key", "zamowienie-42").contentType(MediaType.APPLICATION_JSON).content(req))
                    .andExpect(jsonPath("$.id").value(id));

            String[] event = received.poll(10, TimeUnit.SECONDS);
            assertThat(event).as("webhook").isNotNull();
            String t = event[0].replaceAll("t=(\\d+),v1=.*", "$1");
            assertThat(event[0]).endsWith("v1=" + WebhookSender.sign(secret, t + "." + event[1]));
            assertThat(event[1]).contains("\"type\":\"job.succeeded\"").contains(id);

            for (int i = 0; i < 100 && !mvc.perform(get("/v1/text-to-speech/jobs/" + id).header("Authorization", "Bearer " + key))
                    .andReturn().getResponse().getContentAsString().contains("\"delivered\""); i++) Thread.sleep(50);
            mvc.perform(get("/v1/text-to-speech/jobs/" + id).header("Authorization", "Bearer " + key))
                    .andExpect(jsonPath("$.status").value("succeeded"))
                    .andExpect(jsonPath("$.audio_url").value("/v1/text-to-speech/jobs/" + id + "/audio"))
                    .andExpect(jsonPath("$.webhook.status").value("delivered"));
            byte[] audio = mvc.perform(get("/v1/text-to-speech/jobs/" + id + "/audio").header("Authorization", "Bearer " + key))
                    .andExpect(status().isOk()).andExpect(content().contentType("audio/wav"))
                    .andReturn().getResponse().getContentAsByteArray();
            // Dwa akapity = dwie części złączone przerwą
            assertThat(WavUtils.durationMs(audio)).isGreaterThan(2000);
            verify(engine, atLeast(2)).synthesize(any());

            // Zadanie innego użytkownika jest niewidoczne
            String other = newKey(user("api-jobs2@test.pl", "PRO"));
            mvc.perform(get("/v1/text-to-speech/jobs/" + id).header("Authorization", "Bearer " + other))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("job_not_found"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webhookTargetsInPrivateNetworksAreRejectedInProduction() {
        var strict = new WebhookSender(false);
        assertThatThrownBy(() -> strict.validate("http://example.com/hook")).hasMessageContaining("https");
        assertThatThrownBy(() -> strict.validate("https://127.0.0.1/hook")).hasMessageContaining("wewnętrznej");
        assertThatThrownBy(() -> strict.validate("https://10.0.0.5/hook")).hasMessageContaining("wewnętrznej");
        assertThatThrownBy(() -> strict.validate("https://169.254.169.254/latest")).hasMessageContaining("wewnętrznej");
        assertThatThrownBy(() -> strict.validate("https://user:pass@93.184.215.14/")).hasMessageContaining("logowania");
        strict.validate("https://93.184.215.14/hook");
    }
}
