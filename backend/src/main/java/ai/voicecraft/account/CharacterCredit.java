package ai.voicecraft.account;

import jakarta.persistence.*;
import java.time.Instant;

/** Zakup pakietu znaków (zapis rozliczeniowy). */
@Entity
@Table(name = "character_credits")
public class CharacterCredit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String userId;

    @Column(nullable = false)
    public String packId;

    public long characters;
    public int amountMinor;

    @Column(nullable = false)
    public String currency;

    /** stripe | demo */
    @Column(nullable = false)
    public String source;

    /** Identyfikator sesji Checkout — chroni przed podwójnym naliczeniem przy powtórzonym webhooku. */
    public String reference;

    public Instant createdAt = Instant.now();
}
