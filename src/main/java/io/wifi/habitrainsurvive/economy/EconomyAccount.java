package io.wifi.habitrainsurvive.economy;

import java.util.UUID;

public class EconomyAccount {
    public UUID uuid;
    public String name;
    public double balance;
    public long lastUpdated;

    public EconomyAccount() {
    }

    public EconomyAccount(UUID uuid, String name, double balance) {
        this.uuid = uuid;
        this.name = name;
        this.balance = balance;
        this.lastUpdated = System.currentTimeMillis();
    }
}
