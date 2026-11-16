package ai.voicecraft.account;

/**
 * Jednorazowe pakiety znaków — do kupienia bez subskrypcji (także w planie Free).
 * Znaki z pakietu nie wygasają i są zużywane dopiero po wyczerpaniu miesięcznego limitu planu.
 * Cena jest tu jedynym źródłem prawdy: Checkout Stripe dostaje ją jako price_data (bez cennika w Stripe).
 */
public enum CreditPack {
    SMALL("Pakiet S", 25_000, 1_900),
    MEDIUM("Pakiet M", 100_000, 5_900),
    LARGE("Pakiet L", 500_000, 24_900);

    public static final String CURRENCY = "pln";

    public final String displayName;
    public final long characters;
    /** Cena brutto w groszach. */
    public final int priceMinor;

    CreditPack(String displayName, long characters, int priceMinor) {
        this.displayName = displayName;
        this.characters = characters;
        this.priceMinor = priceMinor;
    }

    public record Dto(String id, String name, long characters, int priceMinor, String currency) {}

    public Dto dto() {
        return new Dto(name(), displayName, characters, priceMinor, CURRENCY);
    }

    public static CreditPack parse(String id) {
        try {
            return CreditPack.valueOf(String.valueOf(id).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Nieznany pakiet: " + id);
        }
    }
}
