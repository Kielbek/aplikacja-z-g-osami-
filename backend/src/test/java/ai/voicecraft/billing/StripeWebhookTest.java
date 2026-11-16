package ai.voicecraft.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StripeWebhookTest {
    @Autowired
    MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions send(String payload, String secret) throws Exception {
        long t = Instant.now().getEpochSecond();
        String sig = StripeSignature.sign(t + "." + payload, secret);
        return mvc.perform(post("/api/billing/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=" + t + ",v1=" + sig).content(payload));
    }

    @Test
    void signatureVerification() {
        long t = Instant.now().getEpochSecond();
        String payload = "{\"id\":\"evt\"}";
        String good = "t=" + t + ",v1=" + StripeSignature.sign(t + "." + payload, "whsec_test");
        assertThat(StripeSignature.verify(payload, good, "whsec_test", Instant.now())).isTrue();
        assertThat(StripeSignature.verify(payload + " ", good, "whsec_test", Instant.now())).isFalse();
        assertThat(StripeSignature.verify(payload, good, "inny", Instant.now())).isFalse();
        // Stary podpis (powtórzenie po > 5 min) jest odrzucany
        assertThat(StripeSignature.verify(payload, good, "whsec_test", Instant.now().plusSeconds(600))).isFalse();
        assertThat(StripeSignature.verify(payload, "zly-naglowek", "whsec_test", Instant.now())).isFalse();
    }

    @Test
    void webhookActivatesAndCancelsPlan() throws Exception {
        var reg = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t")
                .content("{\"name\":\"Pay\",\"email\":\"pay@test.pl\",\"password\":\"Haslo12345\",\"acceptTerms\":true}")).andReturn();
        Cookie c = reg.getResponse().getCookie("vc_session");
        String uid = reg.getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");

        send("{\"id\":\"evt_a\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"client_reference_id\":\"" + uid
                + "\",\"customer\":\"cus_t\",\"subscription\":\"sub_t\",\"metadata\":{\"planId\":\"PRO\"}}}}", "whsec_test")
                .andExpect(status().isOk());
        mvc.perform(get("/api/account/usage").cookie(c)).andExpect(jsonPath("$.plan.id").value("PRO"));

        send("{\"id\":\"evt_b\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"sub_t\",\"customer\":\"cus_t\","
                + "\"status\":\"active\",\"items\":{\"data\":[{\"price\":{\"id\":\"price_business\"}}]}}}}", "whsec_test")
                .andExpect(status().isOk());
        mvc.perform(get("/api/account/usage").cookie(c)).andExpect(jsonPath("$.plan.id").value("BUSINESS"));

        send("{\"id\":\"evt_c\",\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":{\"id\":\"sub_t\",\"customer\":\"cus_t\"}}}", "zly")
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/account/usage").cookie(c)).andExpect(jsonPath("$.plan.id").value("BUSINESS"));

        send("{\"id\":\"evt_c\",\"type\":\"customer.subscription.deleted\",\"data\":{\"object\":{\"id\":\"sub_t\",\"customer\":\"cus_t\"}}}", "whsec_test")
                .andExpect(status().isOk());
        mvc.perform(get("/api/account/usage").cookie(c)).andExpect(jsonPath("$.plan.id").value("FREE"));
    }

    @Test
    void inlinePricedYearlySubscriptionUsesMetadataAndMarksIntroUsed() throws Exception {
        var reg = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "t")
                .content("{\"name\":\"Y\",\"email\":\"yearly@test.pl\",\"password\":\"Haslo12345\",\"acceptTerms\":true}")).andReturn();
        Cookie c = reg.getResponse().getCookie("vc_session");
        String uid = reg.getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/billing/config").cookie(c)).andExpect(jsonPath("$.introEligible").value(true))
                .andExpect(jsonPath("$.introPercentOff").value(50));

        send("{\"id\":\"evt_y1\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"client_reference_id\":\"" + uid
                + "\",\"customer\":\"cus_y\",\"subscription\":\"sub_y\",\"metadata\":{\"planId\":\"STARTER\",\"interval\":\"month\",\"intro\":\"true\"}}}}", "whsec_test")
                .andExpect(status().isOk());
        // Zmiana na rozliczenie roczne z ceną inline (nieznany identyfikator ceny) — plan z metadanych subskrypcji
        send("{\"id\":\"evt_y2\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"sub_y\",\"customer\":\"cus_y\","
                + "\"status\":\"active\",\"metadata\":{\"planId\":\"PRO\"},\"items\":{\"data\":[{\"price\":{\"id\":\"price_inline_1\",\"recurring\":{\"interval\":\"year\"}}}]}}}}", "whsec_test")
                .andExpect(status().isOk());
        mvc.perform(get("/api/account/usage").cookie(c)).andExpect(jsonPath("$.plan.id").value("PRO"))
                .andExpect(jsonPath("$.billingInterval").value("year"));
        mvc.perform(get("/api/billing/config").cookie(c)).andExpect(jsonPath("$.introEligible").value(false));
    }
}
