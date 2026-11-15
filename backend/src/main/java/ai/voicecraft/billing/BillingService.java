package ai.voicecraft.billing;

import ai.voicecraft.account.Account;
import ai.voicecraft.account.AccountRepository;
import ai.voicecraft.account.AccountService;
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
                "subscriptionStatus", a.subscriptionStatus == null ? "" : a.subscriptionStatus);
    }

    @Transactional
    public String checkout(String planId) {
        requireStripe();
        SubscriptionPlan plan = parse(planId);
        if (plan == SubscriptionPlan.FREE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Aby przejść na plan Free, anuluj subskrypcję w panelu płatności.");
        }
        String price = props.billing().prices() == null ? null : props.billing().prices().get(plan.name());
        if (price == null || price.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Plan " + plan.displayName + " nie ma skonfigurowanej ceny w Stripe.");
        }
        Account a = accounts.get();
        if (a.stripeSubscriptionId != null && isActive(a.subscriptionStatus)) {
            // Zmiana istniejącej subskrypcji odbywa się w panelu klienta Stripe
            return stripe.createPortalSession(a.stripeCustomerId, props.publicUrl() + "/settings/subscription");
        }
        if (a.stripeCustomerId == null) a.stripeCustomerId = stripe.createCustomer(a.email, a.userId);
        return stripe.createCheckoutSession(a.stripeCustomerId, price, a.userId, plan.name(),
                props.publicUrl() + "/settings/subscription?checkout=success",
                props.publicUrl() + "/settings/subscription?checkout=cancelled");
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
            case "checkout.session.completed" -> {
                String userId = str(obj.get("client_reference_id"));
                Map<String, Object> meta = (Map<String, Object>) obj.getOrDefault("metadata", Map.of());
                if (userId != null && accountRepo.existsById(userId)) {
                    Account a = accountRepo.findById(userId).orElseThrow();
                    a.stripeCustomerId = str(obj.get("customer"));
                    a.stripeSubscriptionId = str(obj.get("subscription"));
                    a.subscriptionStatus = "active";
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
        SubscriptionPlan plan = planForPrice(item == null ? null : str(((Map<String, Object>) item.get("price")).get("id")));
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
                .map(e -> parseOrNull(e.getKey()))
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
