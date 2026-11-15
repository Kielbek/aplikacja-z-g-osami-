package ai.voicecraft.billing;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Przetworzone zdarzenia Stripe — Stripe może dostarczyć to samo zdarzenie wielokrotnie. */
@Entity
@Table(name = "stripe_events")
public class StripeEvent {
    @Id
    public String id;

    public String type;
    public Instant processedAt = Instant.now();
}
