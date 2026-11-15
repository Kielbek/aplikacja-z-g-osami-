package ai.voicecraft.user;

import jakarta.persistence.*;
import java.time.Instant;

/** Jednorazowy token resetu hasła. W bazie przechowywany jest wyłącznie skrót SHA-256. */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {
    @Id
    @Column(length = 64)
    public String tokenHash;

    @Column(nullable = false)
    public String userId;

    public Instant expiresAt;
    public Instant usedAt;
}
