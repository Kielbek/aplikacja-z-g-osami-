package ai.voicecraft.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.audio.WavUtils;
import ai.voicecraft.auth.MailService;
import ai.voicecraft.engine.EngineClient;
import ai.voicecraft.engine.EngineDtos;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Weryfikacja adresu e-mail przed generowaniem i akceptacja regulaminu przy rejestracji. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"voicecraft.auth.require-email-verification=true", "voicecraft.auth.registrations-per-ip-hour=3"})
class EmailVerificationTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    EngineClient engine;

    @MockitoBean
    MailService mail;

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t").content(body);
    }

    @Test
    void generationRequiresVerifiedEmailAndTermsAreMandatory() throws Exception {
        var caps = new EngineDtos.Capabilities(true, false, true, true, true, List.of("pl"));
        var health = new EngineDtos.Health("ok", "demo", null, null, 24000, caps, null);
        when(engine.health(anyBoolean())).thenReturn(health);
        when(engine.requireOnline()).thenReturn(health);
        byte[] wav = WavUtils.encode(new float[24000], 24000);
        when(engine.synthesize(any())).thenReturn(new EngineDtos.Audio(wav, "demo", 1000));

        mvc.perform(json(post("/api/auth/register"), "{\"name\":\"T\",\"email\":\"noterms@test.pl\",\"password\":\"Haslo12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Zaakceptuj regulamin i politykę prywatności."));

        Cookie c = mvc.perform(json(post("/api/auth/register"),
                        "{\"name\":\"T\",\"email\":\"verify@test.pl\",\"password\":\"Haslo12345\",\"acceptTerms\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(false))
                .andReturn().getResponse().getCookie("vc_session");
        var body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("verify@test.pl"), contains("potwierdź"), body.capture());
        String token = body.getValue().replaceAll("(?s).*verify-email\\?token=([A-Za-z0-9_-]+).*", "$1");

        String project = mvc.perform(get("/api/projects").cookie(c)).andReturn().getResponse().getContentAsString()
                .replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
        String segment = mvc.perform(get("/api/projects/" + project).cookie(c)).andReturn().getResponse().getContentAsString()
                .replaceAll(".*?\"segments\":\\[\\{\"id\":\"([^\"]+)\".*", "$1");
        String gen = "/api/projects/" + project + "/segments/" + segment + "/generate";
        mvc.perform(json(post(gen).cookie(c), "")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        // Ponowna wysyłka ograniczona czasowo
        mvc.perform(json(post("/api/auth/verify-email/resend").cookie(c), "")).andExpect(status().isTooManyRequests());

        mvc.perform(json(post("/api/auth/verify-email"), "{\"token\":\"zly\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/auth/verify-email"), "{\"token\":\"" + token + "\"}")).andExpect(status().isOk());
        mvc.perform(json(post("/api/auth/verify-email"), "{\"token\":\"" + token + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/me").cookie(c)).andExpect(jsonPath("$.emailVerified").value(true));
        mvc.perform(json(post(gen).cookie(c), "")).andExpect(status().isOk());

        // Zmiana adresu wymaga ponownego potwierdzenia
        mvc.perform(json(patch("/api/account").cookie(c), "{\"email\":\"nowy@test.pl\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(c)).andExpect(jsonPath("$.emailVerified").value(false));
        mvc.perform(json(post(gen).cookie(c), "")).andExpect(status().isForbidden());
    }

    @Test
    void registrationIsRateLimitedPerIp() throws Exception {
        int ok = 0;
        for (int i = 0; i < 5; i++) {
            var r = mvc.perform(json(post("/api/auth/register").with(req -> {
                req.setRemoteAddr("10.9.9.9");
                return req;
            }), "{\"name\":\"T\",\"email\":\"farm" + i + "@test.pl\",\"password\":\"Haslo12345\",\"acceptTerms\":true}")).andReturn();
            if (r.getResponse().getStatus() == 200) ok++;
            else assertThat(r.getResponse().getStatus()).isEqualTo(429);
        }
        assertThat(ok).isEqualTo(3);
    }
}
