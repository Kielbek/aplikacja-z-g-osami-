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

    private final GpuQueue queue;

    public EngineController(EngineClient engine, GpuQueue queue) {
        this.engine = engine;
        this.queue = queue;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(defaultValue = "false") boolean refresh) {
        var h = engine.health(refresh);
        var out = new LinkedHashMap<String, Object>();
        out.put("url", engine.baseUrl());
        out.put("online", h != null);
        var q = queue.stats();
        out.put("queue", Map.of("capacity", q.capacity(), "running", q.running(), "waiting", q.waitingTotal()));
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
