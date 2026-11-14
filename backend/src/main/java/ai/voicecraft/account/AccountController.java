package ai.voicecraft.account;

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
}
