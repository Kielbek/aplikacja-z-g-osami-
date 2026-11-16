package ai.voicecraft.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.voicecraft.account.AccountService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Pakiety znaków kupowane bez subskrypcji: naliczanie z webhooka, idempotencja i kolejność zużycia (najpierw plan). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CreditPackTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    AccountService accounts;

    private String[] register(String email) throws Exception {
        var r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t")
                .content("{\"name\":\"K\",\"email\":\"" + email + "\",\"password\":\"Haslo12345\",\"acceptTerms\":true}")).andReturn();
        return new String[] {r.getResponse().getCookie("vc_session").getValue(),
                r.getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1")};
    }

    private void webhook(String payload) throws Exception {
        long t = Instant.now().getEpochSecond();
        mvc.perform(post("/api/billing/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=" + t + ",v1=" + StripeSignature.sign(t + "." + payload, "whsec_test")).content(payload))
                .andExpect(status().isOk());
    }

    private static String session(String evt, String type, String uid, String status, int amount) {
        return "{\"id\":\"" + evt + "\",\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"cs_pack_" + uid + "\",\"client_reference_id\":\""
                + uid + "\",\"customer\":\"cus_p\",\"payment_status\":\"" + status + "\",\"amount_total\":" + amount
                + ",\"metadata\":{\"kind\":\"credits\",\"packId\":\"SMALL\"}}}}";
    }

    @Test
    void paidPackIsCreditedOnceAndUsedAfterPlanLimit() throws Exception {
        String[] u = register("pack@test.pl");
        Cookie c = new Cookie("vc_session", u[0]);
        mvc.perform(get("/api/billing/packs").cookie(c)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].characters").value(25000));

        // Płatność odroczona: najpierw „unpaid” (nic), potem async_payment_succeeded (dopisanie), powtórka nic nie zmienia
        webhook(session("evt_p1", "checkout.session.completed", u[1], "unpaid", 1900));
        mvc.perform(get("/api/account/usage").cookie(c)).andExpect(jsonPath("$.extraCharacters").value(0));
        webhook(session("evt_p2", "checkout.session.async_payment_succeeded", u[1], "paid", 1900));
        webhook(session("evt_p3", "checkout.session.async_payment_succeeded", u[1], "paid", 1900));
        mvc.perform(get("/api/account/usage").cookie(c))
                .andExpect(jsonPath("$.extraCharacters").value(25000))
                .andExpect(jsonPath("$.plan.id").value("FREE")); // pakiet nie zmienia planu ani nie tworzy subskrypcji

        // Najpierw limit planu (Free: 10 000), nadwyżka z pakietu
        accounts.recordUsage(u[1], "segment", 9_000, 1000);
        accounts.recordUsage(u[1], "segment", 3_000, 1000);
        mvc.perform(get("/api/account/usage").cookie(c))
                .andExpect(jsonPath("$.charactersUsed").value(10000))
                .andExpect(jsonPath("$.extraCharacters").value(23000));
    }

    @Test
    void demoModeGrantsPackWithoutPayment() throws Exception {
        Cookie c = new Cookie("vc_session", register("packdemo@test.pl")[0]);
        mvc.perform(post("/api/billing/packs/checkout").cookie(c).header("X-Requested-With", "t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"packId\":\"MEDIUM\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.granted").value(true))
                .andExpect(jsonPath("$.extraCharacters").value(100000));
        mvc.perform(post("/api/billing/packs/checkout").cookie(c).header("X-Requested-With", "t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"packId\":\"XXL\"}"))
                .andExpect(status().isBadRequest());
    }
}
