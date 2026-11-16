package ai.voicecraft.billing;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Minimalny klient REST API Stripe (bez SDK) — tylko wywołania potrzebne aplikacji. */
@Component
public class StripeClient {
    private final AppProperties props;
    private final RestClient http = RestClient.create("https://api.stripe.com/v1");

    public StripeClient(AppProperties props) {
        this.props = props;
    }

    public String createCustomer(String email, String userId) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("email", email);
        form.add("metadata[userId]", userId);
        return String.valueOf(post("/customers", form).get("id"));
    }

    public String createCheckoutSession(String customerId, String priceId, String userId, String planId, String successUrl, String cancelUrl) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("mode", "subscription");
        form.add("customer", customerId);
        form.add("line_items[0][price]", priceId);
        form.add("line_items[0][quantity]", "1");
        form.add("client_reference_id", userId);
        form.add("metadata[userId]", userId);
        form.add("metadata[planId]", planId);
        form.add("subscription_data[metadata][userId]", userId);
        form.add("subscription_data[metadata][planId]", planId);
        form.add("allow_promotion_codes", "true");
        form.add("success_url", successUrl);
        form.add("cancel_url", cancelUrl);
        return String.valueOf(post("/checkout/sessions", form).get("url"));
    }

    /** Jednorazowa płatność za pakiet znaków (bez subskrypcji). Cena przekazywana inline jako price_data. */
    public String createPackCheckoutSession(String customerId, String userId, ai.voicecraft.account.CreditPack pack,
                                            String successUrl, String cancelUrl) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("mode", "payment");
        form.add("customer", customerId);
        form.add("line_items[0][price_data][currency]", ai.voicecraft.account.CreditPack.CURRENCY);
        form.add("line_items[0][price_data][unit_amount]", String.valueOf(pack.priceMinor));
        form.add("line_items[0][price_data][product_data][name]", "Narrivy — " + pack.displayName + " ("
                + String.format(java.util.Locale.forLanguageTag("pl-PL"), "%,d", pack.characters) + " znaków)");
        form.add("line_items[0][quantity]", "1");
        form.add("client_reference_id", userId);
        form.add("metadata[userId]", userId);
        form.add("metadata[kind]", "credits");
        form.add("metadata[packId]", pack.name());
        form.add("invoice_creation[enabled]", "true");
        form.add("success_url", successUrl);
        form.add("cancel_url", cancelUrl);
        return String.valueOf(post("/checkout/sessions", form).get("url"));
    }

    public String createPortalSession(String customerId, String returnUrl) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("customer", customerId);
        form.add("return_url", returnUrl);
        return String.valueOf(post("/billing_portal/sessions", form).get("url"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, LinkedMultiValueMap<String, String> form) {
        try {
            return http.post().uri(path)
                    .header("Authorization", "Bearer " + props.billing().stripeSecretKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Operator płatności odrzucił żądanie (" + e.getStatusCode().value() + ").");
        }
    }
}
