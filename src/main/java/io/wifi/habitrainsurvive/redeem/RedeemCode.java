package io.wifi.habitrainsurvive.redeem;

import java.util.*;

public class RedeemCode {
    public String code;
    public int maxUses;
    public double rewardCoins;
    public List<String> serializedItems = new ArrayList<>();
    public String grantGroup = "";
    public long expiresAt;
    public Set<UUID> usedBy = new HashSet<>();

    public RedeemCode() {
    }

    public RedeemCode(String code, int maxUses, double rewardCoins, List<String> serializedItems, String grantGroup, long durationHours) {
        this.code = code;
        this.maxUses = maxUses;
        this.rewardCoins = rewardCoins;
        if (serializedItems != null) {
            this.serializedItems.addAll(serializedItems);
        }
        this.grantGroup = grantGroup != null ? grantGroup : "";
        this.expiresAt = System.currentTimeMillis() + durationHours * 3600_000L;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public int getRemainingUses() {
        return Math.max(0, maxUses - (usedBy != null ? usedBy.size() : 0));
    }
}
