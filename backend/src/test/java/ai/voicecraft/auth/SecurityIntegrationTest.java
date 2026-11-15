package ai.voicecraft.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Logowanie, sesje, izolacja danych między użytkownikami i ochrona CSRF — na pełnym kontekście aplikacji. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {
    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "test").content(body);
    }

    private Cookie register(String email) throws Exception {
        MvcResult r = mvc.perform(json(post("/api/auth/register"),
                        "{\"name\":\"Test\",\"email\":\"" + email + "\",\"password\":\"Haslo12345\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie c = r.getResponse().getCookie("vc_session");
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isTrue();
        return c;
    }

    @Test
    void apiRequiresSession() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/config")).andExpect(status().isOk());
    }

    @Test
    void stateChangingRequestsRequireCsrfHeader() throws Exception {
        Cookie c = register("csrf@test.pl");
        mvc.perform(post("/api/projects").cookie(c).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/projects").cookie(c), "{}")).andExpect(status().isOk());
    }

    @Test
    void usersCannotAccessEachOthersProjects() throws Exception {
        Cookie a = register("a@test.pl");
        Cookie b = register("b@test.pl");
        String body = mvc.perform(json(post("/api/projects").cookie(a), "{\"name\":\"Projekt A\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/projects/" + id).cookie(a)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + id).cookie(b)).andExpect(status().isNotFound());
        mvc.perform(json(delete("/api/projects/" + id).cookie(b), "")).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + id + "/events").cookie(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects").cookie(b))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Projekt A"))));
    }

    @Test
    void newAccountsStartOnFreePlanWithWelcomeProject() throws Exception {
        Cookie c = register("free@test.pl");
        mvc.perform(get("/api/account/usage").cookie(c))
                .andExpect(jsonPath("$.plan.id").value("FREE"))
                .andExpect(jsonPath("$.projectsUsed").value(1));
    }

    @Test
    void duplicateEmailAndWeakPasswordAreRejected() throws Exception {
        register("dup@test.pl");
        mvc.perform(json(post("/api/auth/register"), "{\"name\":\"X\",\"email\":\"DUP@test.pl\",\"password\":\"Haslo12345\"}"))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/auth/register"), "{\"name\":\"X\",\"email\":\"weak@test.pl\",\"password\":\"krotkie\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginIsRateLimited() throws Exception {
        register("limit@test.pl");
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/api/auth/login"), "{\"email\":\"limit@test.pl\",\"password\":\"zle-haslo\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(json(post("/api/auth/login"), "{\"email\":\"limit@test.pl\",\"password\":\"Haslo12345\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void changingPasswordInvalidatesOtherSessions() throws Exception {
        Cookie old = register("pw@test.pl");
        MvcResult r = mvc.perform(json(post("/api/auth/password").cookie(old),
                        "{\"currentPassword\":\"Haslo12345\",\"newPassword\":\"NoweHaslo123\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie fresh = r.getResponse().getCookie("vc_session");
        mvc.perform(get("/api/projects").cookie(old)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/projects").cookie(fresh)).andExpect(status().isOk());
        mvc.perform(json(post("/api/auth/login"), "{\"email\":\"pw@test.pl\",\"password\":\"NoweHaslo123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        mvc.perform(get("/api/projects").cookie(new Cookie("vc_session", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.invalid")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void planSwitchAffectsCurrentUser() throws Exception {
        Cookie c = register("plan@test.pl");
        mvc.perform(json(put("/api/account/plan").cookie(c), "{\"planId\":\"PRO\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/account/usage").cookie(c)).andExpect(jsonPath("$.plan.id").value("PRO"));
    }

    @Test
    void deletedAccountCannotLogIn() throws Exception {
        Cookie c = register("bye@test.pl");
        mvc.perform(json(post("/api/auth/delete-account").cookie(c), "{\"password\":\"Haslo12345\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/projects").cookie(c)).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/auth/login"), "{\"email\":\"bye@test.pl\",\"password\":\"Haslo12345\"}"))
                .andExpect(status().isUnauthorized());
    }
}
