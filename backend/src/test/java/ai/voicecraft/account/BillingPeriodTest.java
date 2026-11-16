package ai.voicecraft.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Limity odnawiają się co miesiąc od dnia aktywacji planu — także przy subskrypcji rocznej. */
class BillingPeriodTest {
    private static LocalDate start(LocalDate anchor) {
        var a = new Account();
        a.billingPeriodStart = anchor;
        return AccountService.currentPeriodStart(a);
    }

    @Test
    void periodRollsMonthlyFromAnchor() {
        LocalDate today = LocalDate.now();
        assertThat(start(today)).isEqualTo(today);
        assertThat(start(today.minusDays(3)).isAfter(today.minusMonths(1))).isTrue();
        LocalDate old = today.minusMonths(14).minusDays(2);
        LocalDate s = start(old);
        assertThat(s).isBeforeOrEqualTo(today);
        assertThat(s.plusMonths(1)).isAfter(today);
        assertThat(s).isEqualTo(old.plusMonths(java.time.temporal.ChronoUnit.MONTHS.between(old, s)));
    }

    @Test
    void yearlyIsTwoMonthsFreeAndPricesAreExposed() {
        assertThat(SubscriptionPlan.PRO.yearlyPriceMinor()).isEqualTo(SubscriptionPlan.PRO.monthlyPriceMinor * 10);
        assertThat(SubscriptionPlan.FREE.dto().monthlyPriceMinor()).isZero();
        assertThat(SubscriptionPlan.STARTER.dto().yearlyPriceMinor()).isEqualTo(29_000);
    }
}
