package ai.voicecraft.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    @Id
    public String id;

    @Column(nullable = false, unique = true)
    public String email;

    /** BCrypt; null dla kont utworzonych wyłącznie przez Google. */
    public String passwordHash;

    public String fullName;

    @Column(unique = true)
    public String googleSub;

    public String pictureUrl;

    /** Zwiększana przy zmianie hasła / „wyloguj wszędzie” — unieważnia wydane sesje. */
    public int tokenVersion;

    public Instant createdAt = Instant.now();
    public Instant lastLoginAt;

    /** Potwierdzenie adresu e-mail (link z wiadomości lub logowanie przez Google). */
    public Instant emailVerifiedAt;

    /** Akceptacja regulaminu i polityki prywatności przy rejestracji. */
    public Instant termsAcceptedAt;
    public String termsVersion;
}
