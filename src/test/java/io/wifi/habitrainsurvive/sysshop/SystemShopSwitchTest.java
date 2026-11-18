package io.wifi.habitrainsurvive.sysshop;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemShopSwitchTest {
    private static final long NOW = 1_800_000_000_000L;
    private static SystemShopItem item() {
        SystemShopItem item = new SystemShopItem("{}", "test", 10, 3);
        item.pressureUpdatedAt = NOW; return item;
    }
    @Test void missingSwitchAndQuotaFieldsPreserveLegacyBehavior() {
        var gson = new Gson();
        var category = gson.fromJson("{\"id\":\"old\",\"items\":[]}", SystemShopCategory.class);
        assertTrue(category.dynamicEnabled);
        category.purchaseLimits.validate();
        assertEquals(0, category.purchaseLimits.personal.daily);
        SystemShopItem item = gson.fromJson("{\"buyPrice\":10,\"sellPrice\":3}", SystemShopItem.class);
        item.migrate(NOW);
        assertTrue(item.dynamicEnabled);
        assertNotNull(item.id); assertFalse(item.id.isBlank());
        assertEquals(0, item.purchaseLimits.global.weekly);
    }
    @Test void fixedQuotesUseBaseEvenAtDynamicBoundsAndClearPressureOnCommit() {
        SystemShopItem item = item(); item.pressure = Math.log(2); item.dynamicEnabled = false;
        var buy = DynamicShopPrice.quote(item, true, 64, NOW);
        assertEquals(64000, buy.cents()); assertEquals(0, buy.pressure());
        assertEquals(19200, DynamicShopPrice.quote(item, false, 64, NOW).cents());
        assertEquals(Math.log(2), item.pressure); // Pure quotes do not mutate.
        DynamicShopPrice.apply(item, buy); assertEquals(0, item.pressure);
    }
    @Test void categoryCanForceFixedQuoteWhileItemSwitchStaysOn() {
        SystemShopItem item = item(); item.pressure = Math.log(1.5);
        assertTrue(item.dynamicEnabled);
        assertEquals(1000, DynamicShopPrice.quote(item, true, 1, NOW, false).cents());
        assertTrue(DynamicShopPrice.quote(item, true, 1, NOW, true).cents() > 1000);
        assertFalse(item.experiencePrice.enabled());
        item.experiencePrice = new io.wifi.habitrainsurvive.economy.ExperienceCost("POINTS", 120);
        var copy = item.copy();
        assertTrue(copy.experiencePrice.points());
        copy.experiencePrice.amount = 1;
        assertEquals(120, item.experiencePrice.amount);
        var damaged = new com.google.gson.Gson().fromJson("{\"mode\":\"BAD\",\"amount\":5}", io.wifi.habitrainsurvive.economy.ExperienceCost.class);
        damaged.migrate();
        assertFalse(damaged.enabled());
    }
    @Test void fixedPerUnitRoundingMatchesSinglesAndNeverProfits() {
        SystemShopItem item = new SystemShopItem("{}", "test", 0.015, 0.015); item.dynamicEnabled = false;
        assertEquals(128, DynamicShopPrice.quote(item, true, 64, NOW).cents());
        assertEquals(64, DynamicShopPrice.quote(item, false, 64, NOW).cents());
        assertEquals(64 * DynamicShopPrice.quote(item, true, 1, NOW).cents(), DynamicShopPrice.quote(item, true, 64, NOW).cents());
    }
    @Test void copyKeepsIdentityAndPolicyDraftsAreIndependent() {
        SystemShopItem item = item(); item.dynamicEnabled = false; item.purchaseLimits.personal.daily = 5;
        SystemShopItem copy = item.copy();
        assertEquals(item.id, copy.id); assertFalse(copy.dynamicEnabled);
        copy.purchaseLimits.personal.daily = 99; copy.purchaseLimits.global.weekly = 50;
        assertEquals(5, item.purchaseLimits.personal.daily); assertEquals(0, item.purchaseLimits.global.weekly);
    }
    @Test void switchesAndIdsSurviveReloadAndMigration() {
        SystemShopItem item = item(); item.dynamicEnabled = false; item.purchaseLimits.global.weekly = 20;
        var gson = new Gson(); SystemShopItem restored = gson.fromJson(gson.toJson(item), SystemShopItem.class); restored.migrate(NOW);
        assertEquals(item.id, restored.id); assertFalse(restored.dynamicEnabled); assertEquals(20, restored.purchaseLimits.global.weekly);
        restored.migrate(NOW + 1000); assertEquals(item.id, restored.id);
    }
    @Test void disablingDynamicDoesNotEnableDisabledBuyOrInvalidQuantities() {
        SystemShopItem item = item(); item.buyPrice = 0; item.dynamicEnabled = false;
        assertThrows(IllegalArgumentException.class, () -> DynamicShopPrice.quote(item, true, 1, NOW));
        assertThrows(IllegalArgumentException.class, () -> DynamicShopPrice.quote(item, false, 0, NOW));
    }
}
