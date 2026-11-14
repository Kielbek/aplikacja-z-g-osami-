package ai.voicecraft.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/engine")
public class EngineController {
    private final EngineClient engine;

    public EngineController(EngineClient engine) {
        this.engine = engine;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(defaultValue = "false") boolean refresh) {
        var h = engine.health(refresh);
        var out = new LinkedHashMap<String, Object>();
        out.put("url", engine.baseUrl());
        out.put("online", h != null);
        if (h == null) {
            out.put("mode", "offline");
            out.put("message", "Serwis silnika TTS nie odpowiada. Uruchom tts-engine (uvicorn app.main:app --port 8808).");
            return out;
        }
        out.put("mode", h.mode());
        out.put("engine", "VoxCPM2");
        out.put("model", h.model());
        out.put("device", h.device());
        out.put("sampleRate", h.sampleRate());
        out.put("capabilities", h.capabilities());
        out.put("message", h.message());
        return out;
    }
}
