package ai.voicecraft.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ai.voicecraft.config.AppProperties;
import ai.voicecraft.engine.EngineDtos.SynthesizeRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Modal: długie żądanie POST kończy się 303 — klient pobiera wynik GET-em, z nagłówkiem Authorization. */
class EngineClientRedirectTest {

    @Test
    void followsSeeOtherForPostWithAuthorization() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getQuery();
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + (q == null ? "" : "?" + q)
                    + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            ex.getRequestBody().readAllBytes();
            if ("POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Location", "/v1/synthesize?__modal_attempt_token=a1");
                ex.sendResponseHeaders(303, -1);
            } else if (q != null && q.endsWith("a1")) {
                ex.getResponseHeaders().add("Location", "/v1/synthesize?__modal_attempt_token=a2");
                ex.sendResponseHeaders(303, -1);
            } else {
                byte[] body = "RIFFwav".getBytes(StandardCharsets.US_ASCII);
                ex.getResponseHeaders().add("X-Engine-Mode", "voxcpm2");
                ex.getResponseHeaders().add("X-Duration-Ms", "1234");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var props = new AppProperties(null, List.of(), new AppProperties.Engine(url, Duration.ofSeconds(2),
                    Duration.ofSeconds(5), "k".repeat(40)), false, null, null, null, null, null, null);
            var client = new EngineClient(props, new GpuQueue(1, Duration.ofSeconds(5)), Duration.ofSeconds(5));
            var audio = client.synthesize(new SynthesizeRequest("Cześć", "pl", null, null));
            assertThat(new String(audio.wav(), StandardCharsets.US_ASCII)).isEqualTo("RIFFwav");
            assertThat(audio.engineMode()).isEqualTo("voxcpm2");
            assertThat(seen).hasSize(3);
            assertThat(seen.get(0)).startsWith("POST /v1/synthesize ");
            assertThat(seen.get(1)).startsWith("GET /v1/synthesize?__modal_attempt_token=a1");
            assertThat(seen.get(2)).startsWith("GET /v1/synthesize?__modal_attempt_token=a2");
            assertThat(seen).allMatch(s -> s.endsWith("auth=Bearer " + "k".repeat(40)));
        } finally {
            server.stop(0);
        }
    }
}
