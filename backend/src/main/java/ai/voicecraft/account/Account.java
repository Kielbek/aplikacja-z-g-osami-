package ai.voicecraft.account;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** Konto użytkownika (w wersji demo — pojedynczy użytkownik). */
@Entity
@Table(name = "account")
public class Account {
    @Id
    public Long id = 1L;

    // Profil
    public String fullName = "Marta Nowak";
    public String email = "marta@studio-glos.pl";
    public String company = "Studio Głos";
    public String role = "Producentka audio";

    // Subskrypcja
    public String plan = "Pro";
    public long monthlyCharacterLimit = 200_000;
    public int monthlyMinuteLimit = 240;
    public LocalDate billingPeriodStart = LocalDate.now().withDayOfMonth(1);
    public LocalDate renewsOn = LocalDate.now().withDayOfMonth(1).plusMonths(1);

    // Eksport
    public String exportFormat = "mp3";
    public int mp3Bitrate = 192;
    public int pauseBetweenSegmentsMs = 350;
    public String fileNamePattern = "{project}_{date}";

    // Preferencje
    public String theme = "light";
    public String defaultLanguage = "pl";
    public String defaultVoiceId = "pl-anna";
    public boolean autoSplitOnPaste = true;
    public boolean confirmBeforeDelete = true;
    public boolean playAfterGenerate = true;
    public boolean emailNotifications = false;

    public Instant updatedAt = Instant.now();
}
