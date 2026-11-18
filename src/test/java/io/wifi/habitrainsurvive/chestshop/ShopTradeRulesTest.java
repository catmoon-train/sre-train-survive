package io.wifi.habitrainsurvive.chestshop;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ShopTradeRulesTest {
    @Test void rejectsNonFiniteNegativeAndSubCentPrices() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 0.001, 1e20}) {
            assertFalse(ShopTradeRules.price(value, true));
            assertEquals(-1, ShopTradeRules.total(value, 1));
        }
        assertTrue(ShopTradeRules.price(0, true));
        assertFalse(ShopTradeRules.price(0, false));
        assertTrue(ShopTradeRules.price(0.01, false));
    }
    @Test void quantitiesMustBePositiveAndTotalsStayInCents() {
        for (int amount : new int[]{0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) assertEquals(-1, ShopTradeRules.total(1, amount));
        assertEquals(0.03, ShopTradeRules.total(0.01, 3));
        assertEquals(3.33, ShopTradeRules.total(1.11, 3));
        assertEquals(-1, ShopTradeRules.total(0, 64));
    }
    @Test void staffCanManageWithoutBecomingOwnerAndNullCannotManage() {
        ChestShop shop = new ChestShop();
        shop.ownerUuid = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        shop.staff.add(staff);
        assertTrue(shop.canManage(shop.ownerUuid));
        assertTrue(shop.canManage(staff));
        assertFalse(shop.canManage(UUID.randomUUID()));
        assertFalse(shop.canManage(null));
        shop.staff = null;
        assertFalse(shop.canManage(staff));
    }
}
