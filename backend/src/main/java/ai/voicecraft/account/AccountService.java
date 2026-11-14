package ai.voicecraft.account;

import ai.voicecraft.history.HistoryRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository repo;
    private final HistoryRepository history;

    public AccountService(AccountRepository repo, HistoryRepository history) {
        this.repo = repo;
        this.history = history;
    }

    @Transactional
    public Account get() {
        return repo.findById(1L).orElseGet(() -> repo.save(new Account()));
    }

    @Transactional
    public Account update(Map<String, Object> patch) {
        Account a = get();
        patch.forEach((k, v) -> {
            switch (k) {
                case "fullName" -> a.fullName = str(v);
                case "email" -> a.email = str(v);
                case "company" -> a.company = str(v);
                case "role" -> a.role = str(v);
                case "exportFormat" -> a.exportFormat = oneOf(str(v), "mp3", "wav");
                case "mp3Bitrate" -> a.mp3Bitrate = ((Number) v).intValue();
                case "pauseBetweenSegmentsMs" -> a.pauseBetweenSegmentsMs = Math.max(0, Math.min(3000, ((Number) v).intValue()));
                case "fileNamePattern" -> a.fileNamePattern = str(v);
                case "theme" -> a.theme = oneOf(str(v), "light", "dark", "system");
                case "defaultLanguage" -> a.defaultLanguage = str(v);
                case "defaultVoiceId" -> a.defaultVoiceId = str(v);
                case "autoSplitOnPaste" -> a.autoSplitOnPaste = (Boolean) v;
                case "confirmBeforeDelete" -> a.confirmBeforeDelete = (Boolean) v;
                case "playAfterGenerate" -> a.playAfterGenerate = (Boolean) v;
                case "emailNotifications" -> a.emailNotifications = (Boolean) v;
                default -> throw new IllegalArgumentException("Nieznane pole ustawień: " + k);
            }
        });
        a.updatedAt = Instant.now();
        return a;
    }

    public Map<String, Object> usage() {
        Account a = get();
        Instant since = a.billingPeriodStart.atStartOfDay(ZoneId.systemDefault()).toInstant();
        long chars = history.sumCharactersSince(since);
        long ms = history.sumDurationSince(since);
        return Map.of(
                "plan", a.plan,
                "periodStart", a.billingPeriodStart.toString(),
                "renewsOn", a.renewsOn.toString(),
                "charactersUsed", chars,
                "characterLimit", a.monthlyCharacterLimit,
                "minutesUsed", Math.round(ms / 600.0) / 100.0,
                "minuteLimit", a.monthlyMinuteLimit,
                "generations", history.countSince(since));
    }

    public void assertWithinLimit(int characters) {
        Account a = get();
        Instant since = a.billingPeriodStart.atStartOfDay(ZoneId.systemDefault()).toInstant();
        if (history.sumCharactersSince(since) + characters > a.monthlyCharacterLimit) {
            throw new ai.voicecraft.common.ApiException(org.springframework.http.HttpStatus.PAYMENT_REQUIRED,
                    "Przekroczono miesięczny limit znaków planu " + a.plan + ".");
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String oneOf(String v, String... allowed) {
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException("Niedozwolona wartość: " + v);
    }
}
