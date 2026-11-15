package ai.voicecraft.account;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** Profil, ustawienia i subskrypcja użytkownika (relacja 1:1 z User). */
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    public String userId;

    // Profil
    public String fullName;
    public String email;
    public String company;
    public String role;

    // Subskrypcja
    @Enumerated(EnumType.STRING)
    public SubscriptionPlan planId = SubscriptionPlan.FREE;
    /** Stripe: identyfikatory klienta i subskrypcji oraz jej status. */
    public String stripeCustomerId;
    public String stripeSubscriptionId;
    public String subscriptionStatus;
    public LocalDate billingPeriodStart = LocalDate.now().withDayOfMonth(1);
    public LocalDate renewsOn = LocalDate.now().withDayOfMonth(1).plusMonths(1);

    // Eksport
    public String exportFormat = "mp3";
    public int mp3Bitrate = 192;
    public int pauseBetweenSegmentsMs = 350;
    public String fileNamePattern = "{project}_{date}";
    /** Docelowa głośność eksportu w LUFS (np. "-16") lub "off". */
    public String exportLoudness = "-16";

    // Preferencje
    public String theme = "light";
    public String defaultLanguage = "pl";
    public String defaultVoiceId = "voice_warm_velvet";
    public boolean autoSplitOnPaste = true;
    public boolean confirmBeforeDelete = true;
    public boolean playAfterGenerate = true;
    public boolean emailNotifications = false;

    public Instant updatedAt = Instant.now();
}
