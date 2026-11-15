package ai.voicecraft.user;

import jakarta.persistence.*;
import java.time.Instant;

/** Jednorazowy token potwierdzenia adresu e-mail (w bazie wyłącznie skrót SHA-256). */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {
    @Id
    @Column(length = 64)
    public String tokenHash;

    @Column(nullable = false)
    public String userId;

    /** Adres, który token potwierdza — po zmianie adresu stary link przestaje działać. */
    @Column(nullable = false)
    public String email;

    public Instant expiresAt;
    public Instant usedAt;
}
