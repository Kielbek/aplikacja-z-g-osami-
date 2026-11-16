package ai.voicecraft.billing;

import ai.voicecraft.account.Account;
import ai.voicecraft.account.AccountRepository;
import ai.voicecraft.account.AccountService;
import ai.voicecraft.account.CreditPack;
import ai.voicecraft.account.SubscriptionPlan;
import ai.voicecraft.common.ApiException;
import ai.voicecraft.config.AppProperties;
import ai.voicecraft.security.CurrentUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subskrypcje przez Stripe. Plan konta zmienia się WYŁĄCZNIE na podstawie zweryfikowanych
 * zdarzeń webhooka — przekierowanie z Checkout niczego nie aktywuje.
 */
@Service
public class BillingService {
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final AppProperties props;
    private final StripeClient stripe;
    private final AccountService accounts;
    private final AccountRepository accountRepo;
    private final StripeEventRepository processed;

    public BillingService(AppProperties props, StripeClient stripe, AccountService accounts, AccountRepository accountRepo,
                          StripeEventRepository processed) {
        this.props = props;
        this.stripe = stripe;
        this.accounts = accounts;
        this.accountRepo = accountRepo;
        this.processed = processed;
    }

    public Map<String, Object> config() {
        Account a = accounts.get();
        return Map.of(
                "stripeEnabled", props.billing().stripeEnabled(),
                "demoPlanSwitch", props.billing().demoPlanSwitch(),
                "hasSubscription", a.stripeSubscriptionId != null,
                "subscriptionStatus", a.subscriptionStatus == null ? "" : a.subscriptionStatus,
                "billingInterval", a.billingInterval == null ? "" : a.billingInterval,
                "introEligible", introEligible(a),
                "introPercentOff", SubscriptionPlan.INTRO_PERCENT_OFF,
                "yearlyMonthsCharged", SubscriptionPlan.YEARLY_MONTHS_CHARGED);
    }

    /** Zniżka na pierwszy miesiąc: tylko pierwsza subskrypcja konta. */
    private static boolean introEligible(Account a) {
        return !a.introOfferUsed && a.stripeSubscriptionId == null;
    }

    public static final String INTRO_COUPON_ID = "NARRIVY_FIRST_MONTH_" + SubscriptionPlan.INTRO_PERCENT_OFF;

    @Transactional
    public String checkout(String planId, String interval) {
        requireStripe();
        SubscriptionPlan plan = parse(planId);
        if (plan == SubscriptionPlan.FREE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Aby przejść na plan Free, anuluj subskrypcję w panelu płatności.");
        }
        boolean yearly = "year".equals(interval);
        if (interval != null && !yearly && !"month".equals(interval)) throw new IllegalArgumentException("Nieznany okres rozliczenia: " + interval);
        // Cena ze Stripe (STRIPE_PRICE_PRO / STRIPE_PRICE_PRO_YEARLY), a gdy jej brak — cena z aplikacji (price_data)
        String price = props.billing().prices() == null ? null : props.billing().prices().get(plan.name() + (yearly ? "_YEARLY" : ""));
        Account a = accounts.get();
        if (a.stripeSubscriptionId != null && isActive(a.subscriptionStatus)) {
            // Zmiana istniejącej subskrypcji odbywa się w panelu klienta Stripe
            return stripe.createPortalSession(a.stripeCustomerId, props.publicUrl() + "/settings/subscription");
        }
        if (a.stripeCustomerId == null) a.stripeCustomerId = stripe.createCustomer(a.email, a.userId);
        // Pierwszy miesiąc taniej — tylko przy rozliczeniu miesięcznym (przy rocznym kupon objąłby cały rok)
        String coupon = !yearly && introEligible(a)
                ? stripe.ensureCoupon(INTRO_COUPON_ID, SubscriptionPlan.INTRO_PERCENT_OFF, "Pierwszy miesiąc −" + SubscriptionPlan.INTRO_PERCENT_OFF + "%")
                : null;
        return stripe.createCheckoutSession(a.stripeCustomerId, price, plan, yearly, a.userId, coupon,
                props.publicUrl() + "/settings/subscription?checkout=success",
                props.publicUrl() + "/settings/subscription?checkout=cancelled");
    }

    // ------------------------------------------------------------ pakiety znaków (bez subskrypcji)

    public List<CreditPack.Dto> packs() {
        return java.util.Arrays.stream(CreditPack.values()).map(CreditPack::dto).toList();
    }

    /**
     * Zakup pakietu znaków. Ze Stripe: adres płatności (znaki dopisuje dopiero webhook po opłaceniu).
     * Bez Stripe, w trybie deweloperskim (DEMO_PLAN_SWITCH): znaki dopisywane od razu, bez opłaty.
     */
    @Transactional
    public Map<String, Object> buyPack(String packId) {
        CreditPack pack = CreditPack.parse(packId);
        Account a = accounts.get();
        if (!props.billing().stripeEnabled()) {
            if (!props.billing().demoPlanSwitch()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Płatności nie są skonfigurowane (STRIPE_SECRET_KEY).");
            }
            accounts.grantCredits(a.userId, pack, "demo", null);
            return Map.of("granted", true, "extraCharacters", accounts.creditBalance(a.userId));
        }
        if (a.stripeCustomerId == null) a.stripeCustomerId = stripe.createCustomer(a.email, a.userId);
        String url = stripe.createPackCheckoutSession(a.stripeCustomerId, a.userId, pack,
                props.publicUrl() + "/settings/subscription?checkout=success&pack=" + pack.name(),
                props.publicUrl() + "/settings/subscription?checkout=cancelled");
        return Map.of("url", url);
    }

    public String portal() {
        requireStripe();
        Account a = accounts.get();
        if (a.stripeCustomerId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Nie masz jeszcze subskrypcji.");
        return stripe.createPortalSession(a.stripeCustomerId, props.publicUrl() + "/settings/subscription");
    }

    /** Obsługa webhooka. Zwraca false, gdy podpis jest nieprawidłowy. */
    @Transactional
    @SuppressWarnings("unchecked")
    public boolean handleWebhook(String payload, String signature) {
        if (!StripeSignature.verify(payload, signature, props.billing().stripeWebhookSecret(), Instant.now())) return false;
        Map<String, Object> event = JsonParserFactory.getJsonParser().parseMap(payload);
        String id = String.valueOf(event.get("id"));
        String type = String.valueOf(event.get("type"));
        if (processed.existsById(id)) return true; // zdarzenie już obsłużone
        Map<String, Object> obj = (Map<String, Object>) ((Map<String, Object>) event.get("data")).get("object");

        switch (type) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                String userId = str(obj.get("client_reference_id"));
                Map<String, Object> meta = (Map<String, Object>) obj.getOrDefault("metadata", Map.of());
                if ("credits".equals(str(meta.get("kind")))) {
                    applyPackPayment(obj, userId, meta);
                } else if (type.endsWith("completed") && userId != null && accountRepo.existsById(userId)) {
                    Account a = accountRepo.findById(userId).orElseThrow();
                    a.stripeCustomerId = str(obj.get("customer"));
                    a.stripeSubscriptionId = str(obj.get("subscription"));
                    a.subscriptionStatus = "active";
                    if ("true".equals(str(meta.get("intro")))) a.introOfferUsed = true;
                    if (meta.get("interval") != null) a.billingInterval = str(meta.get("interval"));
                    SubscriptionPlan plan = parseOrNull(str(meta.get("planId")));
                    if (plan != null) accounts.changePlan(userId, plan);
                }
            }
            case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" ->
                    applySubscription(obj, type.endsWith("deleted"));
            default -> log.debug("Pominięto zdarzenie Stripe {}", type);
        }
        var e = new StripeEvent();
        e.id = id;
        e.type = type;
        processed.save(e);
        return true;
    }

    /** Opłacona sesja pakietu znaków. Płatności odroczone (np. przelew) dopisują znaki dopiero po zdarzeniu async_payment_succeeded. */
    private void applyPackPayment(Map<String, Object> session, String userId, Map<String, Object> meta) {
        if (!"paid".equals(str(session.get("payment_status")))) {
            log.info("Sesja pakietu {} czeka na płatność", session.get("id"));
            return;
        }
        if (userId == null || !accountRepo.existsById(userId)) {
            log.warn("Opłacony pakiet dla nieznanego użytkownika {}", userId);
            return;
        }
        CreditPack pack;
        try {
            pack = CreditPack.parse(str(meta.get("packId")));
        } catch (IllegalArgumentException e) {
            log.warn("Opłacona sesja {} z nieznanym pakietem {}", session.get("id"), meta.get("packId"));
            return;
        }
        Object paid = session.get("amount_total");
        if (paid instanceof Number n && n.longValue() < pack.priceMinor) {
            log.warn("Sesja {}: zapłacono {} zamiast {} — znaki nie zostały dopisane", session.get("id"), n, pack.priceMinor);
            return;
        }
        accounts.grantCredits(userId, pack, "stripe", str(session.get("id")));
    }

    @SuppressWarnings("unchecked")
    private void applySubscription(Map<String, Object> sub, boolean deleted) {
        String customer = str(sub.get("customer"));
        Map<String, Object> meta = (Map<String, Object>) sub.getOrDefault("metadata", Map.of());
        Account a = (customer == null ? null : accountRepo.findByStripeCustomerId(customer).orElse(null));
        if (a == null && meta.get("userId") != null) a = accountRepo.findById(str(meta.get("userId"))).orElse(null);
        if (a == null) {
            log.warn("Zdarzenie subskrypcji dla nieznanego klienta {}", customer);
            return;
        }
        String status = deleted ? "canceled" : str(sub.get("status"));
        a.stripeCustomerId = customer;
        a.stripeSubscriptionId = deleted ? null : str(sub.get("id"));
        a.subscriptionStatus = status;

        Map<String, Object> item = firstItem(sub);
        Map<String, Object> price = item == null ? null : (Map<String, Object>) item.get("price");
        SubscriptionPlan plan = planForPrice(price == null ? null : str(price.get("id")));
        if (plan == null) plan = parseOrNull(str(meta.get("planId"))); // cena inline (price_data) — plan z metadanych
        if (price != null && price.get("recurring") instanceof Map<?, ?> rec && rec.get("interval") != null) {
            a.billingInterval = str(rec.get("interval"));
        }
        if (deleted) a.billingInterval = null;
        if (deleted || "canceled".equals(status) || "unpaid".equals(status) || "incomplete_expired".equals(status)) {
            accounts.changePlan(a.userId, SubscriptionPlan.FREE);
        } else if (isActive(status) && plan != null) {
            accounts.changePlan(a.userId, plan);
        }
        // „past_due”: Stripe ponawia płatność — plan pozostaje do czasu anulowania
        Object periodEnd = sub.get("current_period_end");
        if (periodEnd == null && item != null) periodEnd = item.get("current_period_end");
        if (periodEnd instanceof Number n && !deleted) {
            Account fresh = accountRepo.findById(a.userId).orElseThrow();
            fresh.renewsOn = LocalDate.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneOffset.UTC);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstItem(Map<String, Object> sub) {
        Object items = sub.get("items");
        if (!(items instanceof Map<?, ?> m)) return null;
        Object data = m.get("data");
        if (!(data instanceof List<?> l) || l.isEmpty()) return null;
        return (Map<String, Object>) l.getFirst();
    }

    private SubscriptionPlan planForPrice(String priceId) {
        if (priceId == null || props.billing().prices() == null) return null;
        return props.billing().prices().entrySet().stream()
                .filter(e -> priceId.equals(e.getValue()))
                .map(e -> parseOrNull(e.getKey().replace("_YEARLY", "")))
                .findFirst().orElse(null);
    }

    private static boolean isActive(String status) {
        return "active".equals(status) || "trialing".equals(status);
    }

    private void requireStripe() {
        if (!props.billing().stripeEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Płatności nie są skonfigurowane (STRIPE_SECRET_KEY).");
        }
        CurrentUser.id();
    }

    private static SubscriptionPlan parse(String id) {
        SubscriptionPlan p = parseOrNull(id);
        if (p == null) throw new IllegalArgumentException("Nieznany plan: " + id);
        return p;
    }

    private static SubscriptionPlan parseOrNull(String id) {
        try {
            return id == null ? null : SubscriptionPlan.valueOf(id.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
