package ai.voicecraft.billing;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return billing.config();
    }

    @PostMapping("/checkout")
    public Map<String, String> checkout(@RequestBody Map<String, String> body) {
        return Map.of("url", billing.checkout(body.get("planId")));
    }

    @GetMapping("/packs")
    public java.util.List<ai.voicecraft.account.CreditPack.Dto> packs() {
        return billing.packs();
    }

    @PostMapping("/packs/checkout")
    public Map<String, Object> buyPack(@RequestBody Map<String, String> body) {
        return billing.buyPack(body.get("packId"));
    }

    @PostMapping("/portal")
    public Map<String, String> portal() {
        return Map.of("url", billing.portal());
    }

    /** Webhook Stripe — publiczny, uwierzytelniany podpisem Stripe-Signature. */
    @PostMapping(value = "/webhook", consumes = "application/json")
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        return billing.handleWebhook(payload, signature)
                ? ResponseEntity.ok("{\"received\":true}")
                : ResponseEntity.badRequest().body("{\"message\":\"Nieprawidłowy podpis.\"}");
    }
}
