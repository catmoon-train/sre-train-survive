package io.wifi.habitrainsurvive.cmi;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeSlotUpgradesTest {
    @Test void pricesIncreaseBy400PerSuccessfulPurchase() {
        assertEquals(100, HomeSlotUpgrades.nextPrice(0));
        assertEquals(500, HomeSlotUpgrades.nextPrice(1));
        assertEquals(900, HomeSlotUpgrades.nextPrice(2));
        assertEquals(1300, HomeSlotUpgrades.nextPrice(3));
        assertEquals(1700, HomeSlotUpgrades.nextPrice(4));
    }
    @Test void purchasedCapacityAddsToCurrentBaseAndRankChangesDoNotResetIt() {
        assertEquals(5, HomeSlotUpgrades.maximum(5, 0));
        assertEquals(8, HomeSlotUpgrades.maximum(5, 3));
        assertEquals(13, HomeSlotUpgrades.maximum(10, 3));
        assertEquals(8, HomeSlotUpgrades.maximum(5, 3));
        assertEquals(1300, HomeSlotUpgrades.nextPrice(3));
    }
    @Test void oldProfilesDefaultToNoPurchasedSlots() {
        var profile = new Gson().fromJson("{\"lastKnownName\":\"old\",\"homes\":{}}", PlayerProfileData.class);
        assertEquals(0, profile.purchasedHomeSlots);
        assertEquals(100, HomeSlotUpgrades.nextPrice(profile.purchasedHomeSlots));
    }
    @Test void countPersistsAndIsIndependentOfHomesMap() {
        var profile = new PlayerProfileData(); profile.purchasedHomeSlots = 3;
        profile.homes.clear();
        var gson = new Gson(); var restored = gson.fromJson(gson.toJson(profile), PlayerProfileData.class);
        assertEquals(3, restored.purchasedHomeSlots);
        assertEquals(1300, HomeSlotUpgrades.nextPrice(restored.purchasedHomeSlots));
    }
    @Test void invalidAndExhaustedCountsFailWithoutWrapping() {
        assertThrows(IllegalArgumentException.class, () -> HomeSlotUpgrades.nextPrice(-1));
        assertThrows(IllegalArgumentException.class, () -> HomeSlotUpgrades.maximum(5, -1));
        assertThrows(IllegalArgumentException.class, () -> HomeSlotUpgrades.nextPrice(Integer.MAX_VALUE));
        assertEquals(100L + 400L * (Integer.MAX_VALUE - 1), HomeSlotUpgrades.nextPrice(Integer.MAX_VALUE - 1));
        assertEquals(Integer.MAX_VALUE, HomeSlotUpgrades.maximum(100000, Integer.MAX_VALUE - 1));
    }
}
