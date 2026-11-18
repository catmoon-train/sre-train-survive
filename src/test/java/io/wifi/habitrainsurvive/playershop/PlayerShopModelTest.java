package io.wifi.habitrainsurvive.playershop;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlayerShopModelTest {
    private PlayerShop shop(int quantity) {
        PlayerShop shop = new PlayerShop();
        shop.ownerUuid = UUID.randomUUID();
        PlayerShop.Listing listing = new PlayerShop.Listing();
        listing.quantity = quantity;
        listing.unitPrice = 2.5;
        listing.serializedItem = "full-component-template";
        shop.listings.put(listing.id, listing);
        return shop;
    }
    @Test void fiveLevelsHaveExplicitPricesAndCapacities() {
        PlayerShopConfig config = new PlayerShopConfig();
        assertEquals(5000, config.openingCost);
        assertEquals(List.of(0.0, 10000.0, 25000.0, 50000.0, 100000.0), config.levels.stream().map(PlayerShopConfig.Level::upgradeCost).toList());
        assertEquals(List.of(9, 18, 27, 36, 45), config.levels.stream().map(PlayerShopConfig.Level::listings).toList());
        assertEquals(List.of(1728, 3456, 6912, 13824, 27648), config.levels.stream().map(PlayerShopConfig.Level::capacity).toList());
        assertTrue(config.valid(List.of()));
        assertThrows(IllegalArgumentException.class, () -> config.level(0));
        assertThrows(IllegalArgumentException.class, () -> config.level(6));
    }
    @Test void depositChecksTotalCapacityAndNewListingLimitWithoutOverflow() {
        PlayerShop shop = shop(1727);
        PlayerShopConfig config = new PlayerShopConfig();
        assertTrue(shop.canDeposit(config, false, 1));
        assertFalse(shop.canDeposit(config, false, 2));
        assertFalse(shop.canDeposit(config, false, 0));
        assertFalse(shop.canDeposit(config, false, -1));
        assertFalse(shop.canDeposit(config, false, Integer.MAX_VALUE));
        shop.listings.values().iterator().next().quantity = 0;
        while (shop.listings.size() < 9) {
            var listing = new PlayerShop.Listing();
            shop.listings.put(listing.id, listing);
        }
        assertFalse(shop.canDeposit(config, true, 1));
        assertTrue(shop.canDeposit(config, false, 1));
    }
    @Test void adminCannotReduceLimitsBelowOccupiedStockOrListings() {
        PlayerShop shop = shop(1700);
        PlayerShopConfig config = new PlayerShopConfig();
        config.levels.set(0, new PlayerShopConfig.Level(0, 9, 1699));
        assertFalse(config.valid(List.of(shop)));
        assertEquals(1700, shop.stock());
        config = new PlayerShopConfig();
        config.levels.set(1, new PlayerShopConfig.Level(10000, 8, 3456));
        assertFalse(config.valid(List.of(shop)));
        config = new PlayerShopConfig();
        config.openingCost = Double.NaN;
        assertFalse(config.valid(List.of()));
        config = new PlayerShopConfig();
        config.levels.set(2, new PlayerShopConfig.Level(Double.POSITIVE_INFINITY, 27, 6912));
        assertFalse(config.valid(List.of()));
    }
    @Test void configurationDraftDoesNotMutateLiveConfiguration() {
        PlayerShopConfig live = new PlayerShopConfig();
        PlayerShopConfig draft = live.copy();
        draft.openingCost = 1;
        draft.levels.set(0, new PlayerShopConfig.Level(0, 1, 1));
        assertEquals(5000, live.openingCost);
        assertEquals(1728, live.level(1).capacity());
    }
    @Test void failedPaymentAndSelfBuyingNeverTouchStockOrDelivery() {
        PlayerShop shop = shop(64);
        var listing = shop.listings.values().iterator().next();
        AtomicInteger calls = new AtomicInteger();
        assertFalse(shop.purchase(shop.ownerUuid, listing, 0, 1, () -> { calls.incrementAndGet(); return true; }, calls::incrementAndGet));
        assertEquals(0, calls.get());
        assertFalse(shop.purchase(UUID.randomUUID(), listing, 0, 1, () -> false, calls::incrementAndGet));
        assertEquals(64, listing.quantity);
        assertEquals(0, listing.revision);
        assertEquals(0, calls.get());
    }
    @Test void negativeZeroOversizedAndNonFiniteTradesAreRejectedBeforePayment() {
        PlayerShop shop = shop(64);
        var listing = shop.listings.values().iterator().next();
        AtomicInteger calls = new AtomicInteger();
        for (int amount : new int[]{-1, 0, 65, Integer.MAX_VALUE})
            assertFalse(shop.purchase(UUID.randomUUID(), listing, 0, amount, () -> { calls.incrementAndGet(); return true; }, calls::incrementAndGet));
        listing.unitPrice = Double.NaN;
        assertFalse(shop.purchase(UUID.randomUUID(), listing, 0, 1, () -> { calls.incrementAndGet(); return true; }, calls::incrementAndGet));
        assertEquals(0, calls.get());
        assertEquals(64, listing.quantity);
    }
    @Test void purchaseChargesBeforeMutationAndMakesOldSnapshotStale() {
        PlayerShop shop = shop(64);
        var listing = shop.listings.values().iterator().next();
        AtomicInteger delivered = new AtomicInteger();
        UUID buyer = UUID.randomUUID();
        assertTrue(shop.purchase(buyer, listing, 0, 16, () -> {
            assertEquals(64, listing.quantity);
            assertEquals(0, delivered.get());
            return true;
        }, () -> { assertEquals(48, listing.quantity); delivered.addAndGet(16); }));
        assertEquals(16, delivered.get());
        assertEquals(1, listing.revision);
        assertFalse(shop.purchase(buyer, listing, 0, 1, () -> fail("Stale request charged"), () -> fail("Stale delivery")));
        var replacement = new PlayerShop.Listing();
        replacement.id = listing.id;
        replacement.quantity = 48;
        replacement.unitPrice = 2.5;
        shop.listings.put(replacement.id, replacement);
        assertFalse(shop.purchase(buyer, listing, 1, 1, () -> fail("Replaced request charged"), () -> fail("Replaced delivery")));
    }
}
