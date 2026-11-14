package ai.voicecraft.account;

import java.util.List;

/**
 * Plany subskrypcji i ich limity. Jedyne źródło prawdy o tym, co wolno w danym planie —
 * egzekwowane w backendzie (AccountService), prezentowane w interfejsie przez /api/account/plans.
 * Wartość -1 oznacza brak limitu.
 */
public enum SubscriptionPlan {
    FREE("Free", 10_000, 10, 3, 5_000, 1, false, List.of("mp3"), 128, false, 7),
    STARTER("Starter", 50_000, 60, 20, 20_000, 5, false, List.of("mp3", "wav"), 192, true, 30),
    PRO("Pro", 200_000, 240, 100, 100_000, 20, true, List.of("mp3", "wav"), 320, true, 90),
    BUSINESS("Business", 1_000_000, 1_200, -1, 500_000, 100, true, List.of("mp3", "wav"), 320, true, 365);

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

    SubscriptionPlan(String displayName, long monthlyCharacters, int monthlyMinutes, int maxProjects,
                     int maxCharactersPerProject, int maxCustomVoices, boolean voiceCloning, List<String> exportFormats,
                     int maxMp3Bitrate, boolean advancedControls, int historyRetentionDays) {
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
    }

    /** Najniższy plan, który spełnia warunek — do komunikatów „dostępne od planu …”. */
    public static SubscriptionPlan lowest(java.util.function.Predicate<SubscriptionPlan> p) {
        for (SubscriptionPlan plan : values()) if (p.test(plan)) return plan;
        return BUSINESS;
    }

    public record Dto(String id, String name, long monthlyCharacters, int monthlyMinutes, int maxProjects,
                      int maxCharactersPerProject, int maxCustomVoices, boolean voiceCloning, List<String> exportFormats,
                      int maxMp3Bitrate, boolean advancedControls, int historyRetentionDays) {}

    public Dto dto() {
        return new Dto(name(), displayName, monthlyCharacters, monthlyMinutes, maxProjects, maxCharactersPerProject,
                maxCustomVoices, voiceCloning, exportFormats, maxMp3Bitrate, advancedControls, historyRetentionDays);
    }
}
