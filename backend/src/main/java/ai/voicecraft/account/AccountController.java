package ai.voicecraft.account;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    public Account get() {
        return service.get();
    }

    @PatchMapping
    public Account update(@RequestBody Map<String, Object> patch) {
        return service.update(patch);
    }

    @GetMapping("/usage")
    public Map<String, Object> usage() {
        return service.usage();
    }

    @GetMapping("/plans")
    public List<SubscriptionPlan.Dto> plans() {
        return Arrays.stream(SubscriptionPlan.values()).map(SubscriptionPlan::dto).toList();
    }

    /** Zmiana planu (wersja demonstracyjna — bez płatności). */
    @PutMapping("/plan")
    public Account changePlan(@RequestBody Map<String, String> body) {
        String id = body.get("planId");
        SubscriptionPlan plan;
        try {
            plan = SubscriptionPlan.valueOf(String.valueOf(id).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Nieznany plan: " + id);
        }
        return service.changePlan(plan);
    }
}
