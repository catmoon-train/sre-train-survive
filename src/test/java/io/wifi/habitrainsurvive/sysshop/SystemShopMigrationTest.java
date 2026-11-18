package io.wifi.habitrainsurvive.sysshop;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SystemShopMigrationTest {
    private static final long NOW = 1_700_000_000_000L;
    private final Gson gson = new Gson();

    @Test void legacyJsonPreservesPricesNamesAndFullSerializedComponents() {
        SystemShopItem item = gson.fromJson("""
                {"serializedItem":"full-components","displayName":"Legacy","buyPrice":4,"sellPrice":1.5}
                """, SystemShopItem.class);
        item.migrate(NOW);
        assertEquals(4, item.buyPrice);
        assertEquals(1.5, item.sellPrice);
        assertEquals("full-components", item.serializedItem);
        assertEquals("Legacy", item.displayName);
        assertEquals(2, item.pricing.minBuyPrice);
        assertEquals(8, item.pricing.maxBuyPrice);
        assertEquals(0.75, item.pricing.minSellPrice);
        assertEquals(3, item.pricing.maxSellPrice);
        assertEquals(0.5, item.pricing.minMultiplier);
        assertEquals(2, item.pricing.maxMultiplier);
        assertEquals(64, item.pricing.sensitivityVolume);
        assertEquals(1, item.pricing.changePercent);
        assertEquals(6, item.pricing.halfLifeHours);
        assertEquals(0, item.pressure);
        assertEquals(NOW, item.pressureUpdatedAt);
    }

    @Test void stateAndAdminSettingsSurviveJsonRoundTripAndOfflineRecovery() {
        SystemShopItem item = new SystemShopItem("components", "Custom", 20, 10);
        item.pressureUpdatedAt = NOW;
        item.pressure = -0.4;
        item.pricing.halfLifeHours = 2;
        item.pricing.changeAmount = 0.25;
        item.pricing.sensitivityVolume = 128;
        SystemShopItem loaded = gson.fromJson(gson.toJson(item), SystemShopItem.class);
        loaded.migrate(NOW + 2 * 3_600_000);
        assertEquals(-0.4, loaded.pressure);
        assertEquals(NOW, loaded.pressureUpdatedAt);
        assertEquals(128, loaded.pricing.sensitivityVolume);
        assertEquals(0.25, loaded.pricing.changeAmount);
        assertEquals(-0.2, DynamicShopPrice.recovered(loaded, NOW + 2 * 3_600_000), 1e-12);
    }

    @Test void migrationRepairsCorruptNumbersInvertedSpreadsAndMissingMetadata() {
        SystemShopItem item = new SystemShopItem();
        item.buyPrice = 4;
        item.sellPrice = 20;
        item.pressure = Double.NaN;
        item.migrate(NOW);
        assertEquals(4, item.sellPrice);
        assertEquals(0, item.pressure);
        assertNotNull(item.displayName);
        item.pricing.halfLifeHours = Double.POSITIVE_INFINITY;
        item.buyPrice = Double.NaN;
        item.sellPrice = -1;
        item.pressure = Double.POSITIVE_INFINITY;
        item.migrate(NOW);
        assertEquals(0, item.buyPrice);
        assertEquals(0, item.sellPrice);
        assertEquals(6, item.pricing.halfLifeHours);
        assertEquals(0, item.pressure);
        item.pricing.validate(item.buyPrice, item.sellPrice);
    }

    @Test void legacySubCentBasesArePreservedButAdminCurrencyRejectsSubCents() {
        SystemShopItem item = gson.fromJson("{\"buyPrice\":0.015,\"sellPrice\":0.005}", SystemShopItem.class);
        item.migrate(NOW);
        assertEquals(0.015, item.buyPrice);
        assertEquals(0.005, item.sellPrice);
        item.pricing.validate(item.buyPrice, item.sellPrice);
        assertThrows(IllegalArgumentException.class, () -> SystemShopPricing.money(0.015));
        assertEquals(12.34, SystemShopPricing.money(12.34));
    }

    @Test void newDisabledListingCanEnableBasesWithoutBeingAccidentallyFree() {
        SystemShopItem item = new SystemShopItem("components", "new", 0, 0);
        assertThrows(IllegalArgumentException.class, () -> DynamicShopPrice.quote(item, true, 1, NOW));
        item.buyPrice = 10;
        item.sellPrice = 3;
        item.pricing.validate(10, 3);
        assertTrue(DynamicShopPrice.quote(item, true, 1, NOW).cents() > 0);
    }

    @Test void validationRejectsNonFiniteAndInvertedPolicies() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class, () -> SystemShopPricing.money(value));
            assertThrows(IllegalArgumentException.class, () -> SystemShopPricing.defaults(10, 3).validate(value, 3));
        }
        for (Consumer<SystemShopPricing> corrupt : java.util.List.<Consumer<SystemShopPricing>>of(
                p -> p.minMultiplier = 0, p -> p.minMultiplier = 1.1, p -> p.maxMultiplier = 0.9,
                p -> p.maxMultiplier = Double.NaN, p -> p.halfLifeHours = 0,
                p -> p.changePercent = Double.NaN, p -> p.changeAmount = Double.POSITIVE_INFINITY,
                p -> p.sensitivityVolume = 0, p -> p.sensitivityVolume = 1_000_001,
                p -> p.minBuyPrice = 21, p -> p.maxBuyPrice = 9,
                p -> p.minSellPrice = 6, p -> p.maxSellPrice = 21,
                p -> p.minBuyPrice = 1.001)) {
            SystemShopPricing pricing = SystemShopPricing.defaults(10, 3);
            corrupt.accept(pricing);
            assertThrows(IllegalArgumentException.class, () -> pricing.validate(10, 3));
        }
        assertThrows(IllegalArgumentException.class, () -> SystemShopPricing.defaults(10, 11).validate(10, 11));
    }

    @Test void editNamesAndIdsAreBoundedAndSafe() {
        assertEquals("new_category-2", ShopEditValues.id(" NEW_category-2 "));
        assertEquals("§a商城", ShopEditValues.name(" &a商城 "));
        for (String invalid : new String[]{"", "../shop", "a b", "x".repeat(33), "中文"})
            assertThrows(IllegalArgumentException.class, () -> ShopEditValues.id(invalid));
        for (String invalid : new String[]{"", " ", "x".repeat(65), "shop\nname"})
            assertThrows(IllegalArgumentException.class, () -> ShopEditValues.name(invalid));
    }
}
