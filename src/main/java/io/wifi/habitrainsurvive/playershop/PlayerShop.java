package io.wifi.habitrainsurvive.playershop;

import io.wifi.habitrainsurvive.chestshop.ShopTradeRules;
import java.util.*;
import java.util.function.BooleanSupplier;

/** No Minecraft dependencies: templates are full-component, single-item JSON; stock is separate. */
public final class PlayerShop {
    public UUID ownerUuid;
    public String ownerName;
    public int level = 1;
    public Map<String, Listing> listings = new LinkedHashMap<>();
    public static final class Listing {
        public String id = UUID.randomUUID().toString();
        public String serializedItem;
        public String displayName;
        public int quantity;
        public double unitPrice;
        public long revision;
    }
    public long stock() { return listings.values().stream().mapToLong(l -> Math.max(0, l.quantity)).sum(); }
    public boolean current(Listing expected, long revision) {
        return expected != null && listings.get(expected.id) == expected && expected.revision == revision;
    }
    public boolean canDeposit(PlayerShopConfig config, boolean newListing, int amount) {
        PlayerShopConfig.Level limits = config.level(level);
        return amount > 0 && stock() + (long) amount <= limits.capacity()
                && (!newListing || listings.size() < limits.listings());
    }

    /** Caller preflights delivery space. Failed payment/self/stale/invalid requests do not mutate stock. */
    public boolean purchase(UUID buyer, Listing expected, long revision, int amount, BooleanSupplier payment, Runnable delivery) {
        if (ownerUuid == null || buyer == null || ownerUuid.equals(buyer) || !current(expected, revision)
                || amount <= 0 || amount > expected.quantity || ShopTradeRules.total(expected.unitPrice, amount) <= 0) return false;
        if (!payment.getAsBoolean()) return false;
        expected.quantity -= amount;
        expected.revision++;
        delivery.run();
        return true;
    }
}
