package ai.voicecraft.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Weryfikacja nagłówka Stripe-Signature (schemat v1):
 * podpis = HMAC-SHA256(sekret, "{t}.{payload}"), tolerancja czasu 5 minut (ochrona przed powtórzeniem).
 */
public final class StripeSignature {
    static final long TOLERANCE_SECONDS = 300;

    private StripeSignature() {}

    public static boolean verify(String payload, String header, String secret, Instant now) {
        if (payload == null || header == null || secret == null || secret.isBlank()) return false;
        Long timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) return false;
        if (Math.abs(now.getEpochSecond() - timestamp) > TOLERANCE_SECONDS) return false;
        byte[] expected = sign(timestamp + "." + payload, secret).getBytes(StandardCharsets.UTF_8);
        for (String s : signatures) {
            if (MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.UTF_8))) return true;
        }
        return false;
    }

    public static String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
