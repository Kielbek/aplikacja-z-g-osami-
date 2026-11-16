package ai.voicecraft.account;

import ai.voicecraft.common.ApiException;
import ai.voicecraft.project.ProjectRepository;
import ai.voicecraft.project.VoiceSettings;
import ai.voicecraft.security.CurrentUser;
import ai.voicecraft.usage.UsageEvent;
import ai.voicecraft.usage.UsageRepository;
import ai.voicecraft.user.User;
import ai.voicecraft.user.UserRepository;
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
    private final UsageRepository usage;
    private final ProjectRepository projects;
    private final VoiceRepository voices;
    private final UserRepository users;
    private final ai.voicecraft.auth.EmailVerificationService verification;
    private final CharacterCreditRepository credits;

    public AccountService(AccountRepository repo, UsageRepository usage, ProjectRepository projects,
                          VoiceRepository voices, UserRepository users, ai.voicecraft.auth.EmailVerificationService verification,
                          CharacterCreditRepository credits) {
        this.credits = credits;
        this.verification = verification;
        this.repo = repo;
        this.usage = usage;
        this.projects = projects;
        this.voices = voices;
        this.users = users;
    }

    /** Konto zalogowanego użytkownika. */
    @Transactional
    public Account get() {
        return get(CurrentUser.id());
    }

    @Transactional
    public Account get(String userId) {
        return repo.findById(userId).orElseGet(() -> {
            User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Konto"));
            return repo.save(create(u, SubscriptionPlan.FREE));
        });
    }

    /** Nowe konto z ustawieniami domyślnymi. */
    public static Account create(User u, SubscriptionPlan plan) {
        var a = new Account();
        a.userId = u.id;
        a.email = u.email;
        a.fullName = u.fullName;
        a.planId = plan;
        if (!plan.exportFormats.contains(a.exportFormat)) a.exportFormat = plan.exportFormats.getFirst();
        a.mp3Bitrate = Math.min(a.mp3Bitrate, plan.maxMp3Bitrate);
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
                case "fullName" -> {
                    a.fullName = str(v);
                    users.findById(a.userId).ifPresent(u -> u.fullName = a.fullName);
                }
                case "email" -> changeEmail(a, str(v));
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
                case "exportLoudness" -> a.exportLoudness = oneOf(str(v), "off", "-14", "-16", "-19", "-23");
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

    /** Zmiana planu: z webhooka Stripe lub (tylko dev) przełącznikiem demonstracyjnym. */
    @Transactional
    public Account changePlan(String userId, SubscriptionPlan plan) {
        Account a = get(userId);
        if (a.planId != plan) {
            a.billingPeriodStart = java.time.LocalDate.now();
            a.renewsOn = a.billingPeriodStart.plusMonths(1);
        }
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
        String uid = a.userId;
        var out = new LinkedHashMap<String, Object>();
        out.put("plan", p.dto());
        out.put("periodStart", a.billingPeriodStart.toString());
        out.put("renewsOn", a.renewsOn.toString());
        out.put("charactersUsed", usage.sumPlanCharactersSince(uid, since));
        out.put("characterLimit", p.monthlyCharacters);
        out.put("extraCharacters", creditBalance(uid));
        out.put("minutesUsed", Math.round(usage.sumDurationSince(uid, since) / 600.0) / 100.0);
        out.put("minuteLimit", p.monthlyMinutes);
        out.put("generations", usage.countSince(uid, since));
        out.put("projectsUsed", projects.countByOwnerId(uid));
        out.put("projectLimit", p.maxProjects);
        out.put("customVoicesUsed", voices.countByOwnerId(uid));
        out.put("customVoiceLimit", p.maxCustomVoices);
        return out;
    }

    /**
     * Zapisuje zużycie (niezależnie od historii, której użytkownik może się pozbyć).
     * Najpierw liczy się limit planu, a nadwyżka schodzi z dokupionych pakietów znaków.
     */
    @Transactional
    public void recordUsage(String userId, String kind, int characters, long durationMs) {
        Account a = get(userId);
        long fromPlan = Math.min(characters, planCharactersLeft(a));
        int fromCredits = (int) Math.min(characters - fromPlan, creditBalance(userId));
        usage.save(UsageEvent.of(userId, kind, characters, fromCredits, durationMs));
    }

    /** Saldo dokupionych znaków: kupione minus zużyte z pakietów. */
    public long creditBalance(String userId) {
        return Math.max(0, credits.sumCharacters(userId) - usage.sumCreditCharacters(userId));
    }

    /** Dopisuje pakiet znaków. Idempotentne względem {@code reference} (np. powtórzony webhook Stripe). */
    @Transactional
    public boolean grantCredits(String userId, CreditPack pack, String source, String reference) {
        if (reference != null && credits.existsByReference(reference)) return false;
        get(userId);
        var c = new CharacterCredit();
        c.userId = userId;
        c.packId = pack.name();
        c.characters = pack.characters;
        c.amountMinor = "demo".equals(source) ? 0 : pack.priceMinor;
        c.currency = CreditPack.CURRENCY;
        c.source = source;
        c.reference = reference;
        credits.save(c);
        return true;
    }

    public java.util.List<CharacterCredit> creditPurchases(String userId) {
        return credits.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Ile znaków zostało w limicie planu w bieżącym okresie (0 także po wyczerpaniu minut audio). */
    private long planCharactersLeft(Account a) {
        Instant since = periodStart(a);
        SubscriptionPlan p = a.planId;
        if (usage.sumDurationSince(a.userId, since) >= p.monthlyMinutes * 60_000L) return 0;
        return Math.max(0, p.monthlyCharacters - usage.sumPlanCharactersSince(a.userId, since));
    }

    // ------------------------------------------------------------ egzekwowanie limitów

    /** Limity miesięczne (znaki i minuty audio); po ich wyczerpaniu — dokupione pakiety znaków. */
    public void requireGenerationQuota(int characters) {
        Account a = get();
        verification.requireVerified(a.userId);
        SubscriptionPlan p = a.planId;
        long left = planCharactersLeft(a);
        if (characters <= left) return;
        long extra = creditBalance(a.userId);
        if (characters - left <= extra) return;
        boolean minutes = usage.sumDurationSince(a.userId, periodStart(a)) >= p.monthlyMinutes * 60_000L;
        String reason = minutes
                ? "Wykorzystano miesięczny limit " + p.monthlyMinutes + " minut audio planu " + p.displayName + "."
                : "Wykorzystano miesięczny limit znaków planu " + p.displayName + " (" + fmt(p.monthlyCharacters) + "). Pozostało "
                        + fmt(left) + " znaków, fragment ma " + fmt(characters) + ".";
        String more = extra > 0
                ? " Dodatkowych znaków z pakietów: " + fmt(extra) + " — za mało na ten fragment."
                : "";
        throw ApiException.plan(reason + more + " Dokup pakiet znaków lub zmień plan.");
    }

    /** Generowanie i klonowanie wymagają potwierdzonego adresu e-mail. */
    public void requireVerifiedEmail() {
        verification.requireVerified(CurrentUser.id());
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
        if (p.maxProjects >= 0 && projects.countByOwnerId(CurrentUser.id()) >= p.maxProjects) {
            throw ApiException.plan("Plan " + p.displayName + " pozwala na " + p.maxProjects + " "
                    + plural(p.maxProjects, "projekt", "projekty", "projektów") + ". Usuń nieużywany projekt lub zmień plan.");
        }
    }

    public void requireCustomVoiceSlot() {
        SubscriptionPlan p = plan();
        if (voices.countByOwnerId(CurrentUser.id()) >= p.maxCustomVoices) {
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

    private void changeEmail(Account a, String email) {
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Niepoprawny adres e-mail.");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        User u = users.findById(a.userId).orElseThrow();
        if (!u.email.equals(normalized) && users.existsByEmailIgnoreCase(normalized)) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "Ten adres e-mail jest już używany.");
        }
        boolean changed = !u.email.equals(normalized);
        u.email = normalized;
        a.email = normalized;
        if (changed) verification.emailChanged(u);
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
