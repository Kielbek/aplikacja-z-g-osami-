package ai.voicecraft.voice;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Dowód zgody na klonowanie głosu: kto, kiedy, z jakiego adresu IP, na jaką treść oświadczenia
 * i dla którego nagrania (skrót SHA-256). Zachowywany także po usunięciu głosu lub konta
 * (ochrona przed roszczeniami — art. 17 ust. 3 lit. e RODO).
 */
@Entity
@Table(name = "voice_consents")
public class VoiceConsent {
    @Id
    public String id;

    @Column(nullable = false)
    public String userId;
    public String userEmail;
    public String voiceId;
    public String voiceName;
    public String referenceId;

    @Column(nullable = false, length = 32)
    public String consentVersion;

    @Column(nullable = false, length = 2000)
    public String consentText;

    @Column(nullable = false, length = 64)
    public String audioSha256;
    public long audioBytes;
    public String fileName;
    public String ip;

    @Column(length = 500)
    public String userAgent;
    public Instant createdAt = Instant.now();
}
