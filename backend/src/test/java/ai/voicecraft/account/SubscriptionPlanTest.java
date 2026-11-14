package ai.voicecraft.account;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SubscriptionPlanTest {

    @Test
    void lowestPlanForFeature() {
        assertEquals(SubscriptionPlan.PRO, SubscriptionPlan.lowest(p -> p.voiceCloning));
        assertEquals(SubscriptionPlan.STARTER, SubscriptionPlan.lowest(p -> p.exportFormats.contains("wav")));
        assertEquals(SubscriptionPlan.STARTER, SubscriptionPlan.lowest(p -> p.advancedControls));
    }

    @Test
    void limitsGrowWithPlan() {
        SubscriptionPlan[] plans = SubscriptionPlan.values();
        for (int i = 1; i < plans.length; i++) {
            assertTrue(plans[i].monthlyCharacters > plans[i - 1].monthlyCharacters);
            assertTrue(plans[i].maxCustomVoices > plans[i - 1].maxCustomVoices);
            assertTrue(plans[i].historyRetentionDays > plans[i - 1].historyRetentionDays);
        }
    }
}
