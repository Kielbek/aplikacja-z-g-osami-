package ai.voicecraft.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.engine.EngineClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Foldery projektów: tworzenie, przenoszenie, izolacja użytkowników, usuwanie bez utraty projektów. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FolderIntegrationTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    EngineClient engine;

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t").content(body);
    }

    private Cookie user(String email) throws Exception {
        return mvc.perform(json(post("/api/auth/register"),
                        "{\"name\":\"T\",\"email\":\"" + email + "\",\"password\":\"Haslo12345\",\"acceptTerms\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("vc_session");
    }

    private static String id(String body) {
        return body.replaceAll("(?s).*?\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void foldersOrganizeProjectsPerUser() throws Exception {
        Cookie c = user("folders@test.pl");
        String folder = id(mvc.perform(json(post("/api/folders").cookie(c), "{\"name\":\"  Klient   Ziarno \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Klient Ziarno"))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(json(post("/api/folders").cookie(c), "{\"name\":\"\"}")).andExpect(status().isBadRequest());

        // Nowy projekt prosto w folderze
        String p1 = id(mvc.perform(json(post("/api/projects").cookie(c), "{\"name\":\"Spot\",\"folderId\":\"" + folder + "\"}"))
                .andExpect(jsonPath("$.folderId").value(folder)).andReturn().getResponse().getContentAsString());
        // Projekt powitalny przeniesiony do folderu i z powrotem
        String welcome = mvc.perform(get("/api/projects").cookie(c)).andReturn().getResponse().getContentAsString();
        String p0 = welcome.replaceAll("(?s).*\"id\":\"([^\"]+)\",\"name\":\"Mój pierwszy projekt\".*", "$1");
        mvc.perform(json(put("/api/projects/" + p0 + "/folder").cookie(c), "{\"folderId\":\"" + folder + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.folderId").value(folder));
        mvc.perform(json(put("/api/projects/" + p0 + "/folder").cookie(c), "{\"folderId\":null}"))
                .andExpect(jsonPath("$.folderId").doesNotExist());

        // Cudzy folder i cudzy projekt są niewidoczne (404)
        Cookie other = user("folders-other@test.pl");
        mvc.perform(get("/api/folders").cookie(other)).andExpect(content().json("[]"));
        String theirs = id(mvc.perform(json(post("/api/projects").cookie(other), "{\"name\":\"Cudzy\"}")).andReturn().getResponse().getContentAsString());
        mvc.perform(json(put("/api/projects/" + theirs + "/folder").cookie(other), "{\"folderId\":\"" + folder + "\"}")).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/projects/" + p1 + "/folder").cookie(other), "{\"folderId\":null}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/folders/" + folder).cookie(other).header("X-Requested-With", "t")).andExpect(status().isNotFound());

        // Zmiana nazwy i usunięcie folderu — projekt zostaje, bez folderu
        mvc.perform(json(patch("/api/folders/" + folder).cookie(c), "{\"name\":\"Ziarno 2026\"}")).andExpect(jsonPath("$.name").value("Ziarno 2026"));
        mvc.perform(delete("/api/folders/" + folder).cookie(c).header("X-Requested-With", "t")).andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + p1).cookie(c)).andExpect(status().isOk()).andExpect(jsonPath("$.folderId").doesNotExist());
        assertThat(mvc.perform(get("/api/folders").cookie(c)).andReturn().getResponse().getContentAsString()).isEqualTo("[]");

        // Usunięcie konta usuwa też foldery
        mvc.perform(json(post("/api/folders").cookie(c), "{\"name\":\"Do usunięcia\"}")).andExpect(status().isOk());
        mvc.perform(json(post("/api/auth/delete-account").cookie(c), "{\"password\":\"Haslo12345\"}")).andExpect(status().isOk());
    }
}
