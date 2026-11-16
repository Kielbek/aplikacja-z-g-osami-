package ai.voicecraft.account;

import java.util.List;

/**
 * Plany subskrypcji i ich limity. Jedyne źródło prawdy o tym, co wolno w danym planie —
 * egzekwowane w backendzie (AccountService), prezentowane w interfejsie przez /api/account/plans.
 * Wartość -1 oznacza brak limitu.
 */
public enum SubscriptionPlan {
    FREE("Free", 10_000, 10, 3, 5_000, 1, false, List.of("mp3"), 128, false, 7, 0),
    STARTER("Starter", 50_000, 60, 20, 20_000, 5, false, List.of("mp3", "wav"), 192, true, 30, 2_900),
    PRO("Pro", 200_000, 240, 100, 100_000, 20, true, List.of("mp3", "wav"), 320, true, 90, 7_900),
    BUSINESS("Business", 1_000_000, 1_200, -1, 500_000, 100, true, List.of("mp3", "wav"), 320, true, 365, 29_900);

    public static final String CURRENCY = "pln";
    /** Rozliczenie roczne: płacisz za 10 miesięcy, 2 miesiące gratis (ok. 17% taniej). */
    public static final int YEARLY_MONTHS_CHARGED = 10;
    /** Zniżka na pierwszy miesiąc pierwszej subskrypcji (tylko rozliczenie miesięczne). */
    public static final int INTRO_PERCENT_OFF = 50;

    public final String displayName;
    public final long monthlyCharacters;
    public final int monthlyMinutes;
    public final int maxProjects;
    public final int maxCharactersPerProject;
    public final int maxCustomVoices;
    public final boolean voiceCloning;
    public final List<String> exportFormats;
    public final int maxMp3Bitrate;
    public final boolean advancedControls;
    public final int historyRetentionDays;
    /** Cena brutto za miesiąc w groszach (rozliczenie miesięczne). */
    public final int monthlyPriceMinor;

    SubscriptionPlan(String displayName, long monthlyCharacters, int monthlyMinutes, int maxProjects,
                     int maxCharactersPerProject, int maxCustomVoices, boolean voiceCloning, List<String> exportFormats,
                     int maxMp3Bitrate, boolean advancedControls, int historyRetentionDays, int monthlyPriceMinor) {
        this.displayName = displayName;
        this.monthlyCharacters = monthlyCharacters;
        this.monthlyMinutes = monthlyMinutes;
        this.maxProjects = maxProjects;
        this.maxCharactersPerProject = maxCharactersPerProject;
        this.maxCustomVoices = maxCustomVoices;
        this.voiceCloning = voiceCloning;
        this.exportFormats = exportFormats;
        this.maxMp3Bitrate = maxMp3Bitrate;
        this.advancedControls = advancedControls;
        this.historyRetentionDays = historyRetentionDays;
        this.monthlyPriceMinor = monthlyPriceMinor;
    }

    /** Publiczne API: dostęp, liczba równoległych żądań i limit żądań na minutę. */
    public record ApiLimits(boolean enabled, int concurrency, int requestsPerMinute) {}

    public ApiLimits api() {
        return switch (this) {
            case PRO -> new ApiLimits(true, 2, 60);
            case BUSINESS -> new ApiLimits(true, 5, 300);
            default -> new ApiLimits(false, 0, 0);
        };
    }

    /** Cena za cały rok przy rozliczeniu rocznym. */
    public int yearlyPriceMinor() {
        return monthlyPriceMinor * YEARLY_MONTHS_CHARGED;
    }

    public int priceMinor(boolean yearly) {
        return yearly ? yearlyPriceMinor() : monthlyPriceMinor;
    }

    /** Najniższy plan, który spełnia warunek — do komunikatów „dostępne od planu …”. */
    public static SubscriptionPlan lowest(java.util.function.Predicate<SubscriptionPlan> p) {
        for (SubscriptionPlan plan : values()) if (p.test(plan)) return plan;
        return BUSINESS;
    }

    public record Dto(String id, String name, long monthlyCharacters, int monthlyMinutes, int maxProjects,
                      int maxCharactersPerProject, int maxCustomVoices, boolean voiceCloning, List<String> exportFormats,
                      int maxMp3Bitrate, boolean advancedControls, int historyRetentionDays,
                      int monthlyPriceMinor, int yearlyPriceMinor, String currency,
                      boolean apiAccess, int apiConcurrency, int apiRequestsPerMinute) {}

    public Dto dto() {
        return new Dto(name(), displayName, monthlyCharacters, monthlyMinutes, maxProjects, maxCharactersPerProject,
                maxCustomVoices, voiceCloning, exportFormats, maxMp3Bitrate, advancedControls, historyRetentionDays,
                monthlyPriceMinor, yearlyPriceMinor(), CURRENCY, api().enabled(), api().concurrency(), api().requestsPerMinute());
    }
}
