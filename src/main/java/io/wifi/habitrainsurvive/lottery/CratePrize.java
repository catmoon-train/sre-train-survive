package io.wifi.habitrainsurvive.lottery;

public class CratePrize {
    public String serializedItem;
    public String displayName;
    public int count;
    public double bonusCoins;
    public int weight;
    public boolean rareBroadcast;

    public CratePrize() {
    }

    public CratePrize(String serializedItem, String displayName, int count, double bonusCoins, int weight, boolean rareBroadcast) {
        this.serializedItem = serializedItem;
        this.displayName = displayName;
        this.count = count;
        this.bonusCoins = bonusCoins;
        this.weight = Math.max(1, weight);
        this.rareBroadcast = rareBroadcast;
    }
}
