package ai.voicecraft.account;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.history.HistoryRepository;
import ai.voicecraft.project.ProjectRepository;
import ai.voicecraft.project.VoiceSettings;
import ai.voicecraft.voice.VoiceKind;
import ai.voicecraft.voice.VoiceRepository;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository repo;
    private final HistoryRepository history;
    private final ProjectRepository projects;
    private final VoiceRepository voices;

    public AccountService(AccountRepository repo, HistoryRepository history, ProjectRepository projects, VoiceRepository voices) {
        this.repo = repo;
        this.history = history;
        this.projects = projects;
        this.voices = voices;
    }

    @Transactional
    public Account get() {
        Account a = repo.findById(1L).orElseGet(() -> repo.save(new Account()));
        if (a.planId == null) a.planId = SubscriptionPlan.PRO;
        return a;
    }

    public SubscriptionPlan plan() {
        return get().planId;
    }

    @Transactional
    public Account update(Map<String, Object> patch) {
        Account a = get();
        SubscriptionPlan plan = a.planId;
        patch.forEach((k, v) -> {
            switch (k) {
                case "fullName" -> a.fullName = str(v);
                case "email" -> a.email = str(v);
                case "company" -> a.company = str(v);
                case "role" -> a.role = str(v);
                case "exportFormat" -> {
                    String f = oneOf(str(v), "mp3", "wav");
                    requireExportFormat(plan, f);
                    a.exportFormat = f;
                }
                case "mp3Bitrate" -> {
                    int b = ((Number) v).intValue();
                    if (b > plan.maxMp3Bitrate) {
                        throw ApiException.plan("Plan " + plan.displayName + " pozwala na MP3 do " + plan.maxMp3Bitrate + " kb/s.");
                    }
                    a.mp3Bitrate = b;
                }
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

    /**
     * Zmiana planu. W wersji demonstracyjnej bez płatności — po integracji z operatorem płatności
     * wywoływana z webhooka potwierdzającego zakup.
     */
    @Transactional
    public Account changePlan(SubscriptionPlan plan) {
        Account a = get();
        a.planId = plan;
        // Dopasuj ustawienia eksportu do możliwości nowego planu
        if (!plan.exportFormats.contains(a.exportFormat)) a.exportFormat = plan.exportFormats.getFirst();
        if (a.mp3Bitrate > plan.maxMp3Bitrate) a.mp3Bitrate = plan.maxMp3Bitrate;
        a.updatedAt = Instant.now();
        return a;
    }

    public Map<String, Object> usage() {
        Account a = get();
        SubscriptionPlan p = a.planId;
        Instant since = periodStart(a);
        var out = new LinkedHashMap<String, Object>();
        out.put("plan", p.dto());
        out.put("periodStart", a.billingPeriodStart.toString());
        out.put("renewsOn", a.renewsOn.toString());
        out.put("charactersUsed", history.sumCharactersSince(since));
        out.put("characterLimit", p.monthlyCharacters);
        out.put("minutesUsed", Math.round(history.sumDurationSince(since) / 600.0) / 100.0);
        out.put("minuteLimit", p.monthlyMinutes);
        out.put("generations", history.countSince(since));
        out.put("projectsUsed", projects.count());
        out.put("projectLimit", p.maxProjects);
        out.put("customVoicesUsed", customVoiceCount());
        out.put("customVoiceLimit", p.maxCustomVoices);
        return out;
    }

    // ------------------------------------------------------------ egzekwowanie limitów

    /** Limity miesięczne: znaki i minuty audio. */
    public void requireGenerationQuota(int characters) {
        Account a = get();
        SubscriptionPlan p = a.planId;
        Instant since = periodStart(a);
        long used = history.sumCharactersSince(since);
        if (used + characters > p.monthlyCharacters) {
            long left = Math.max(0, p.monthlyCharacters - used);
            throw ApiException.plan("Wykorzystano miesięczny limit znaków planu " + p.displayName + " ("
                    + fmt(p.monthlyCharacters) + "). Pozostało " + fmt(left) + " znaków, fragment ma " + fmt(characters) + ".");
        }
        if (history.sumDurationSince(since) >= p.monthlyMinutes * 60_000L) {
            throw ApiException.plan("Wykorzystano miesięczny limit " + p.monthlyMinutes + " minut audio planu " + p.displayName + ".");
        }
    }

    public void requireProjectCharacters(int projectCharacters) {
        SubscriptionPlan p = plan();
        if (projectCharacters > p.maxCharactersPerProject) {
            throw ApiException.plan("Projekt ma " + fmt(projectCharacters) + " znaków. Plan " + p.displayName
                    + " pozwala na " + fmt(p.maxCharactersPerProject) + " znaków w jednym projekcie.");
        }
    }

    public void requireProjectSlot() {
        SubscriptionPlan p = plan();
        if (p.maxProjects >= 0 && projects.count() >= p.maxProjects) {
            throw ApiException.plan("Plan " + p.displayName + " pozwala na " + p.maxProjects + " "
                    + plural(p.maxProjects, "projekt", "projekty", "projektów") + ". Usuń nieużywany projekt lub zmień plan.");
        }
    }

    public void requireCustomVoiceSlot() {
        SubscriptionPlan p = plan();
        if (customVoiceCount() >= p.maxCustomVoices) {
            throw ApiException.plan("Plan " + p.displayName + " pozwala na " + p.maxCustomVoices
                    + " " + plural(p.maxCustomVoices, "własny głos", "własne głosy", "własnych głosów") + ". Usuń nieużywany głos lub zmień plan.");
        }
    }

    public void requireCloning() {
        SubscriptionPlan p = plan();
        if (!p.voiceCloning) {
            throw ApiException.plan("Klonowanie głosu jest dostępne od planu "
                    + SubscriptionPlan.lowest(x -> x.voiceCloning).displayName + ".");
        }
    }

    public void requireExportFormat(String format) {
        requireExportFormat(plan(), format);
    }

    /** W planie bez ustawień zaawansowanych zmiana ich wartości jest odrzucana. */
    public void requireAdvancedAllowed(VoiceSettings current, VoiceSettings next) {
        SubscriptionPlan p = plan();
        if (p.advancedControls) return;
        boolean changed = next.cfgValue != current.cfgValue || next.inferenceTimesteps != current.inferenceTimesteps
                || next.normalize != current.normalize || next.denoise != current.denoise
                || !java.util.Objects.equals(next.seed, current.seed);
        if (changed) {
            throw ApiException.plan("Ustawienia zaawansowane są dostępne od planu "
                    + SubscriptionPlan.lowest(x -> x.advancedControls).displayName + ".");
        }
    }

    /** Parametry przekazywane do silnika: bez ustawień zaawansowanych w planach, które ich nie obejmują. */
    public VoiceSettings effectiveSettings(VoiceSettings s) {
        if (plan().advancedControls) return s;
        var d = s.copy();
        var defaults = new VoiceSettings();
        d.cfgValue = defaults.cfgValue;
        d.inferenceTimesteps = defaults.inferenceTimesteps;
        d.normalize = defaults.normalize;
        d.denoise = defaults.denoise;
        d.seed = null;
        return d;
    }

    public int effectiveBitrate() {
        Account a = get();
        return Math.min(a.mp3Bitrate, a.planId.maxMp3Bitrate);
    }

    public Instant historyCutoff() {
        return Instant.now().minus(java.time.Duration.ofDays(plan().historyRetentionDays));
    }

    // ------------------------------------------------------------ pomocnicze

    private static void requireExportFormat(SubscriptionPlan p, String format) {
        if (!p.exportFormats.contains(format)) {
            throw ApiException.plan("Eksport " + format.toUpperCase() + " jest dostępny od planu "
                    + SubscriptionPlan.lowest(x -> x.exportFormats.contains(format)).displayName + ".");
        }
    }

    private long customVoiceCount() {
        return voices.findAll().stream().filter(v -> v.kind != VoiceKind.LIBRARY).count();
    }

    private static Instant periodStart(Account a) {
        return a.billingPeriodStart.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static String fmt(long n) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag("pl-PL")).format(n);
    }

    private static String plural(long n, String one, String few, String many) {
        if (n == 1) return one;
        long d = n % 10, t = n % 100;
        return d >= 2 && d <= 4 && (t < 12 || t > 14) ? few : many;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String oneOf(String v, String... allowed) {
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException("Niedozwolona wartość: " + v);
    }
}
