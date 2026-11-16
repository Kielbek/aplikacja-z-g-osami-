package ai.voicecraft.account;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import ai.voicecraft.security.CurrentUser;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final AccountService service;
    private final AppProperties props;
    private final DataExportService export;

    public AccountController(AccountService service, AppProperties props, DataExportService export) {
        this.service = service;
        this.props = props;
        this.export = export;
    }

    /** Eksport wszystkich danych konta (RODO art. 15 i 20) jako archiwum ZIP. */
    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> exportData() {
        var data = export.prepare();
        String name = "narrivy-dane-" + java.time.LocalDate.now() + ".zip";
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(out -> export.write(data, out));
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

    /**
     * Zmiana planu bez płatności — wyłącznie środowisko deweloperskie (DEMO_PLAN_SWITCH).
     * W produkcji plan zmienia się tylko przez Stripe (webhook).
     */
    @PutMapping("/plan")
    public Account changePlan(@RequestBody Map<String, String> body) {
        if (!props.billing().demoPlanSwitch()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "Zmiana planu odbywa się przez płatności.");
        }
        String id = body.get("planId");
        SubscriptionPlan plan;
        try {
            plan = SubscriptionPlan.valueOf(String.valueOf(id).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Nieznany plan: " + id);
        }
        Account a = service.changePlan(CurrentUser.id(), plan);
        a.billingInterval = plan == SubscriptionPlan.FREE ? null : ("year".equals(body.get("interval")) ? "year" : "month");
        return a;
    }
}
