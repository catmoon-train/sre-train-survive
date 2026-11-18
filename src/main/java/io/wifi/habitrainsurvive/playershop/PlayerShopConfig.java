package io.wifi.habitrainsurvive.playershop;

import io.wifi.habitrainsurvive.chestshop.ShopTradeRules;
import java.util.*;

/** Pure, persisted upgrade model. Capacity counts individual items, not stacks. */
public final class PlayerShopConfig {
    public double openingCost = 5000;
    public List<Level> levels = new ArrayList<>(List.of(
            new Level(0, 9, 1728), new Level(10000, 18, 3456), new Level(25000, 27, 6912),
            new Level(50000, 36, 13824), new Level(100000, 45, 27648)));
    public record Level(double upgradeCost, int listings, int capacity) { }

    public Level level(int level) {
        if (level < 1 || level > 5) throw new IllegalArgumentException("Invalid shop level");
        return levels.get(level - 1);
    }
    public PlayerShopConfig copy() {
        PlayerShopConfig copy = new PlayerShopConfig();
        copy.openingCost = openingCost;
        copy.levels = new ArrayList<>(levels);
        return copy;
    }
    public boolean valid(Collection<PlayerShop> shops) {
        if (!ShopTradeRules.price(openingCost, true) || levels == null || levels.size() != 5) return false;
        int lastListings = 0, lastCapacity = 0;
        for (Level l : levels) {
            if (l == null || !ShopTradeRules.price(l.upgradeCost(), true) || l.listings() < lastListings
                    || l.capacity() < lastCapacity || l.listings() < 1 || l.listings() > 10000
                    || l.capacity() < 1 || l.capacity() > 1_000_000) return false;
            lastListings = l.listings();
            lastCapacity = l.capacity();
        }
        for (PlayerShop shop : shops) {
            if (shop.level < 1 || shop.level > 5 || shop.listings.size() > level(shop.level).listings()
                    || shop.stock() > level(shop.level).capacity()) return false;
        }
        return true;
    }
}
