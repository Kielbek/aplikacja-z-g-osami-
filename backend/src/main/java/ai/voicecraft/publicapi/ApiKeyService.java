package ai.voicecraft.publicapi;

import ai.voicecraft.account.Account;
import ai.voicecraft.account.AccountService;
import ai.voicecraft.account.SubscriptionPlan;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.security.CurrentUser;
import ai.voicecraft.usage.UsageRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Zarządzanie kluczami API i sekretem webhooków (z poziomu zalogowanego użytkownika aplikacji). */
@Service
public class ApiKeyService {
    public static final String KEY_PREFIX = "nrv_live_";
    public static final String SECRET_PREFIX = "whsec_";
    static final int MAX_ACTIVE_KEYS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository keys;
    private final AccountService accounts;
    private final UsageRepository usage;

    public ApiKeyService(ApiKeyRepository keys, AccountService accounts, UsageRepository usage) {
        this.keys = keys;
        this.accounts = accounts;
        this.usage = usage;
    }

    public record KeyDto(String id, String name, String prefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt,
                         long charactersThisPeriod, long requestsThisPeriod) {}

    /** Odpowiedź na utworzenie klucza: jedyny moment, w którym widać pełną wartość. */
    public record CreatedKey(KeyDto key, String secret) {}

    public record Overview(boolean enabled, String plan, String requiredPlan, int concurrency, int requestsPerMinute,
                           long charactersThisPeriod, List<KeyDto> keys) {}

    public Overview overview() {
        Account a = accounts.get();
        SubscriptionPlan.ApiLimits limits = a.planId.api();
        Instant since = accounts.currentPeriodStartInstant(a.userId);
        Map<String, long[]> perKey = new HashMap<>();
        for (Object[] row : usage.apiUsageByKeySince(a.userId, since)) {
            perKey.put(String.valueOf(row[0]), new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        var list = keys.findAllByUserIdOrderByCreatedAtDesc(a.userId).stream().map(k -> dto(k, perKey.get(k.id))).toList();
        return new Overview(limits.enabled(), a.planId.displayName, SubscriptionPlan.lowest(p -> p.api().enabled()).displayName,
                limits.concurrency(), limits.requestsPerMinute(), usage.sumApiCharactersSince(a.userId, since), list);
    }

    @Transactional
    public CreatedKey create(String name) {
        Account a = accounts.get();
        requireApiPlan(a.planId);
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) clean = "Klucz API";
        if (clean.length() > 100) throw new IllegalArgumentException("Nazwa klucza może mieć najwyżej 100 znaków.");
        if (keys.countByUserIdAndRevokedAtIsNull(a.userId) >= MAX_ACTIVE_KEYS) {
            throw new ApiException(HttpStatus.CONFLICT, "Możesz mieć najwyżej " + MAX_ACTIVE_KEYS + " aktywnych kluczy. Unieważnij nieużywany klucz.");
        }
        String secret = KEY_PREFIX + randomToken(30);
        var k = new ApiKey();
        k.id = "key_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        k.userId = a.userId;
        k.name = clean;
        k.prefix = secret.substring(0, KEY_PREFIX.length() + 6);
        k.keyHash = sha256(secret);
        keys.save(k);
        return new CreatedKey(dto(k, null), secret);
    }

    @Transactional
    public KeyDto revoke(String id) {
        ApiKey k = keys.findByIdAndUserId(id, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Klucz API"));
        if (k.revokedAt == null) k.revokedAt = Instant.now();
        return dto(k, null);
    }

    @Transactional
    public KeyDto rename(String id, String name) {
        ApiKey k = keys.findByIdAndUserId(id, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Klucz API"));
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 100) throw new IllegalArgumentException("Nazwa klucza musi mieć od 1 do 100 znaków.");
        k.name = clean;
        return dto(k, null);
    }

    /** Sekret podpisu webhooków; tworzony przy pierwszym odczycie. */
    @Transactional
    public String webhookSecret() {
        Account a = accounts.get();
        if (a.apiWebhookSecret == null) a.apiWebhookSecret = SECRET_PREFIX + randomToken(24);
        return a.apiWebhookSecret;
    }

    @Transactional
    public String rotateWebhookSecret() {
        Account a = accounts.get();
        a.apiWebhookSecret = SECRET_PREFIX + randomToken(24);
        return a.apiWebhookSecret;
    }

    /** Sekret webhooków dowolnego użytkownika (wysyłka z wątku zadań). */
    @Transactional
    public String webhookSecretOf(String userId) {
        Account a = accounts.get(userId);
        if (a.apiWebhookSecret == null) a.apiWebhookSecret = SECRET_PREFIX + randomToken(24);
        return a.apiWebhookSecret;
    }

    // ------------------------------------------------------------ uwierzytelnianie żądań /v1

    /** Aktywny klucz o podanej wartości albo null. Aktualizuje „ostatnie użycie” najwyżej raz na minutę. */
    @Transactional
    public ApiKey authenticate(String secret) {
        if (secret == null || !secret.startsWith(KEY_PREFIX) || secret.length() > 120) return null;
        ApiKey k = keys.findByKeyHash(sha256(secret)).orElse(null);
        if (k == null || !k.active()) return null;
        Instant now = Instant.now();
        if (k.lastUsedAt == null || k.lastUsedAt.isBefore(now.minus(Duration.ofMinutes(1)))) k.lastUsedAt = now;
        return k;
    }

    static void requireApiPlan(SubscriptionPlan plan) {
        if (!plan.api().enabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Dostęp do API jest dostępny od planu "
                    + SubscriptionPlan.lowest(p -> p.api().enabled()).displayName + ". Twój plan: " + plan.displayName + ".", "plan_required");
        }
    }

    private static KeyDto dto(ApiKey k, long[] stats) {
        return new KeyDto(k.id, k.name, k.prefix + "…", k.createdAt, k.lastUsedAt, k.revokedAt,
                stats == null ? 0 : stats[0], stats == null ? 0 : stats[1]);
    }

    static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b).replace('-', 'x').replace('_', 'y');
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
