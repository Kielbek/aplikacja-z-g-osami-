package ai.voicecraft.publicapi;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wysyłka webhooków API. Podpis w nagłówku {@code Narrivy-Signature: t=<unix>,v1=<hex HMAC-SHA256("t.body")>}
 * (ten sam schemat co Stripe — łatwy do zweryfikowania i odporny na powtórzenia).
 *
 * <p>Ochrona SSRF: tylko https (http wyłącznie w trybie deweloperskim), bez przekierowań, a adres docelowy
 * nie może wskazywać sieci wewnętrznej (loopback, prywatne, link-local, metadane chmury).
 */
@Component
public class WebhookSender {
    private final boolean allowPrivate;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public WebhookSender(@Value("${voicecraft.api.allow-private-webhooks:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /** Walidacja adresu przy przyjęciu zadania — błąd od razu, a nie dopiero po syntezie. */
    public void validate(String url) {
        if (url == null) return;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Pole „callback_url” nie jest poprawnym adresem URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(allowPrivate && scheme.equals("http"))) {
            throw new IllegalArgumentException("Pole „callback_url” musi być adresem https://.");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || url.length() > 2048) {
            throw new IllegalArgumentException("Pole „callback_url” musi zawierać nazwę hosta (bez danych logowania).");
        }
        if (!allowPrivate) requirePublic(uri.getHost());
    }

    /** Wysyła zdarzenie; zwraca kod HTTP odpowiedzi (lub -1 przy błędzie połączenia). */
    public int send(String url, String secret, String body) {
        try {
            validate(url);
            long t = System.currentTimeMillis() / 1000;
            var req = HttpRequest.newBuilder(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Narrivy-Webhooks/1.0")
                    .header("Narrivy-Signature", "t=" + t + ",v1=" + sign(secret, t + "." + body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requirePublic(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Nie można odnaleźć hosta z pola „callback_url”: " + host + ".");
        }
        for (InetAddress a : addresses) {
            byte[] b = a.getAddress();
            boolean cgnat = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;    // 100.64.0.0/10
            boolean uniqueLocal = b.length == 16 && (b[0] & 0xfe) == 0xfc;                     // fc00::/7
            if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                    || a.isMulticastAddress() || cgnat || uniqueLocal) {
                throw new IllegalArgumentException("Pole „callback_url” nie może wskazywać adresu w sieci wewnętrznej.");
            }
        }
    }
}
