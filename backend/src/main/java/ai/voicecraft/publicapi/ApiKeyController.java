package ai.voicecraft.publicapi;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Zarządzanie kluczami API z poziomu aplikacji (sesja użytkownika, nie klucz API). */
@RestController
@RequestMapping("/api/api-keys")
public class ApiKeyController {
    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public ApiKeyService.Overview overview() {
        return service.overview();
    }

    @PostMapping
    public ApiKeyService.CreatedKey create(@RequestBody(required = false) Map<String, String> body) {
        return service.create(body == null ? null : body.get("name"));
    }

    @PatchMapping("/{id}")
    public ApiKeyService.KeyDto rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        return service.rename(id, body.get("name"));
    }

    @DeleteMapping("/{id}")
    public ApiKeyService.KeyDto revoke(@PathVariable String id) {
        return service.revoke(id);
    }

    @GetMapping("/webhook-secret")
    public Map<String, String> webhookSecret() {
        return Map.of("secret", service.webhookSecret());
    }

    @PostMapping("/webhook-secret/rotate")
    public Map<String, String> rotateWebhookSecret() {
        return Map.of("secret", service.rotateWebhookSecret());
    }
}
