package ai.voicecraft.account;

import java.util.List;

/**
 * Plany subskrypcji i ich limity. Jedyne źródło prawdy o tym, co wolno w danym planie —
 * egzekwowane w backendzie (AccountService), prezentowane w interfejsie przez /api/account/plans.
 * Wartość -1 oznacza brak limitu.
 */
public enum SubscriptionPlan {
    // Limity porównane z ElevenLabs (XI 2026): Free 10k; Starter ~22 zł / 30k; Creator ~81 zł / 121k; Pro ~366 zł / 600k.
    // Miejsce: WAV 48 kHz ≈ 7 MB na 1000 znaków — każdy plan mieści ok. 10 miesięcy pełnego wykorzystania.
    //          nazwa       znaki/mies. min   MB      znaki/projekt głosy klon   eksport                  kb/s  zaaw.  hist. nagrania  cena
    FREE("Free",           10_000,     20,   250,    5_000,        1,    false, List.of("mp3"),          128,  false, 7,    365,      0),
    STARTER("Starter",     40_000,     80,   3_000,  40_000,       5,    false, List.of("mp3", "wav"),   192,  true,  30,   -1,       1_900),
    PRO("Pro",             200_000,    400,  15_000, 200_000,      20,   true,  List.of("mp3", "wav"),   320,  true,  90,   -1,       6_900),
    BUSINESS("Business",   1_000_000,  2_000, 75_000, 500_000,     100,  true,  List.of("mp3", "wav"),   320,  true,  365,  -1,       29_900);

    public static final String CURRENCY = "pln";
    /** Rozliczenie roczne: płacisz za 10 miesięcy, 2 miesiące gratis (ok. 17% taniej). */
    public static final int YEARLY_MONTHS_CHARGED = 10;
    /** Zniżka na pierwszy miesiąc pierwszej subskrypcji (tylko rozliczenie miesięczne). */
    public static final int INTRO_PERCENT_OFF = 50;

    public final String displayName;
    public final long monthlyCharacters;
    public final int monthlyMinutes;
    /** Miejsce na nagrania w MB (projekty, historia, własne głosy, wyniki API). Liczba projektów jest nieograniczona. */
    public final int storageMb;
    public final int maxCharactersPerProject;
    public final int maxCustomVoices;
    public final boolean voiceCloning;
    public final List<String> exportFormats;
    public final int maxMp3Bitrate;
    public final boolean advancedControls;
    public final int historyRetentionDays;
    /** Jak długo przechowujemy nagrania fragmentów od ich wygenerowania (-1 = bez limitu czasu). */
    public final int audioRetentionDays;
    /** Cena brutto za miesiąc w groszach (rozliczenie miesięczne). */
    public final int monthlyPriceMinor;

    SubscriptionPlan(String displayName, long monthlyCharacters, int monthlyMinutes, int storageMb,
                     int maxCharactersPerProject, int maxCustomVoices, boolean voiceCloning, List<String> exportFormats,
                     int maxMp3Bitrate, boolean advancedControls, int historyRetentionDays, int audioRetentionDays,
                     int monthlyPriceMinor) {
        this.displayName = displayName;
        this.monthlyCharacters = monthlyCharacters;
        this.monthlyMinutes = monthlyMinutes;
        this.storageMb = storageMb;
        this.maxCharactersPerProject = maxCharactersPerProject;
        this.maxCustomVoices = maxCustomVoices;
        this.voiceCloning = voiceCloning;
        this.exportFormats = exportFormats;
        this.maxMp3Bitrate = maxMp3Bitrate;
        this.advancedControls = advancedControls;
        this.historyRetentionDays = historyRetentionDays;
        this.audioRetentionDays = audioRetentionDays;
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

    public long storageBytes() {
        return storageMb * 1_000_000L;
    }

    public record Dto(String id, String name, long monthlyCharacters, int monthlyMinutes, int storageMb, int audioRetentionDays,
                      int maxCharactersPerProject, int maxCustomVoices, boolean voiceCloning, List<String> exportFormats,
                      int maxMp3Bitrate, boolean advancedControls, int historyRetentionDays,
                      int monthlyPriceMinor, int yearlyPriceMinor, String currency,
                      boolean apiAccess, int apiConcurrency, int apiRequestsPerMinute) {}

    public Dto dto() {
        return new Dto(name(), displayName, monthlyCharacters, monthlyMinutes, storageMb, audioRetentionDays, maxCharactersPerProject,
                maxCustomVoices, voiceCloning, exportFormats, maxMp3Bitrate, advancedControls, historyRetentionDays,
                monthlyPriceMinor, yearlyPriceMinor(), CURRENCY, api().enabled(), api().concurrency(), api().requestsPerMinute());
    }
}
