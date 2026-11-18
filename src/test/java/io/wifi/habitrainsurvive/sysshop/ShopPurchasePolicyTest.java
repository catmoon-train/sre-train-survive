package io.wifi.habitrainsurvive.sysshop;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class ShopPurchasePolicyTest {
    private static final List<BiConsumer<ShopPurchaseLimits, Integer>> FIELDS = List.of(
            (p, v) -> p.perTransaction = v, (p, v) -> p.daily = v, (p, v) -> p.weekly = v,
            (p, v) -> p.monthly = v, (p, v) -> p.total = v);

    @Test void defaultsAreUnlimitedAndIndependent() {
        ShopPurchasePolicy policy = new ShopPurchasePolicy();
        policy.validate();
        assertNotSame(policy.personal, policy.global);
        assertEquals(0, policy.personal.perTransaction);
        assertEquals(0, policy.personal.daily);
        assertEquals(0, policy.personal.weekly);
        assertEquals(0, policy.personal.monthly);
        assertEquals(0, policy.personal.total);
        assertEquals(0, policy.global.total);
    }

    @Test void everyLimitRejectsNegativeAndAboveMaximumButAcceptsBothEndpoints() {
        assertEquals(1_000_000_000, ShopPurchaseLimits.MAX);
        for (BiConsumer<ShopPurchaseLimits, Integer> field : FIELDS) {
            for (int invalid : new int[]{-1, Integer.MIN_VALUE, ShopPurchaseLimits.MAX + 1, Integer.MAX_VALUE}) {
                ShopPurchaseLimits limits = new ShopPurchaseLimits();
                field.accept(limits, invalid);
                assertThrows(IllegalArgumentException.class, limits::validate);
            }
            for (int valid : new int[]{0, 1, ShopPurchaseLimits.MAX}) {
                ShopPurchaseLimits limits = new ShopPurchaseLimits();
                field.accept(limits, valid);
                assertDoesNotThrow(limits::validate);
            }
        }
    }

    @Test void limitsCopyRetainsEveryValueWithoutAliasing() {
        ShopPurchaseLimits limits = new ShopPurchaseLimits();
        limits.perTransaction = 1;
        limits.daily = 2;
        limits.weekly = 3;
        limits.monthly = 4;
        limits.total = 5;
        ShopPurchaseLimits copy = limits.copy();
        assertNotSame(limits, copy);
        assertEquals(1, copy.perTransaction);
        assertEquals(2, copy.daily);
        assertEquals(3, copy.weekly);
        assertEquals(4, copy.monthly);
        assertEquals(5, copy.total);
        copy.daily = 99;
        assertEquals(2, limits.daily);
    }

    @Test void policyValidatesBothSectionsAndRejectsNull() {
        for (boolean shared : new boolean[]{false, true}) {
            ShopPurchasePolicy policy = new ShopPurchasePolicy();
            ShopPurchaseLimits limits = shared ? policy.global : policy.personal;
            limits.total = -1;
            assertThrows(IllegalArgumentException.class, policy::validate);
            if (shared) policy.global = null;
            else policy.personal = null;
            assertThrows(IllegalArgumentException.class, policy::validate);
        }
    }

    @Test void policyCopyIsDeepForBothSections() {
        ShopPurchasePolicy policy = new ShopPurchasePolicy();
        policy.personal.daily = 12;
        policy.global.monthly = 200;
        ShopPurchasePolicy copy = policy.copy();
        assertNotSame(policy, copy);
        assertNotSame(policy.personal, copy.personal);
        assertNotSame(policy.global, copy.global);
        assertEquals(12, copy.personal.daily);
        assertEquals(200, copy.global.monthly);
        copy.personal.daily = 4;
        copy.global.monthly = 5;
        assertEquals(12, policy.personal.daily);
        assertEquals(200, policy.global.monthly);
    }

    @Test void migrationInitializesOnlyMissingOptionalSections() {
        Gson gson = new Gson();
        for (String json : new String[]{"{}", "{\"personal\":null,\"global\":null}"}) {
            ShopPurchasePolicy policy = gson.fromJson(json, ShopPurchasePolicy.class);
            policy.migrate();
            policy.validate();
            assertEquals(0, policy.personal.daily);
            assertEquals(0, policy.global.daily);
        }
        ShopPurchasePolicy policy = new ShopPurchasePolicy();
        ShopPurchaseLimits existing = policy.personal;
        existing.total = 77;
        policy.global = null;
        policy.migrate();
        assertSame(existing, policy.personal);
        assertEquals(77, policy.personal.total);
        assertNotNull(policy.global);
    }

    @Test void migrationDoesNotSilentlyReplaceInvalidSettingsOrPartiallyRepair() {
        for (boolean shared : new boolean[]{false, true}) {
            ShopPurchasePolicy policy = new ShopPurchasePolicy();
            ShopPurchaseLimits invalid = shared ? policy.global : policy.personal;
            invalid.weekly = -7;
            if (shared) policy.personal = null;
            else policy.global = null;
            assertThrows(IllegalArgumentException.class, policy::migrate);
            assertSame(invalid, shared ? policy.global : policy.personal);
            assertEquals(-7, invalid.weekly);
            assertNull(shared ? policy.personal : policy.global);
        }
    }

    @Test void gsonRoundTripRetainsPersonalAndGlobalSettings() {
        Gson gson = new Gson();
        ShopPurchasePolicy policy = new ShopPurchasePolicy();
        policy.personal.perTransaction = 4;
        policy.personal.daily = 10;
        policy.global.weekly = 30;
        policy.global.monthly = 100;
        policy.global.total = 1000;
        ShopPurchasePolicy restored = gson.fromJson(gson.toJson(policy), ShopPurchasePolicy.class);
        restored.migrate();
        assertEquals(gson.toJson(policy), gson.toJson(restored));
        assertNotSame(policy.personal, restored.personal);
    }
}
