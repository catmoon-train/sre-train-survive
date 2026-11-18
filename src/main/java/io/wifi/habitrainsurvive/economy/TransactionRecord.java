package io.wifi.habitrainsurvive.economy;

import java.util.UUID;

public class TransactionRecord {
    public long timestamp;
    public UUID from;
    public UUID to;
    public double amount;
    public String reason;

    public TransactionRecord() {
    }

    public TransactionRecord(UUID from, UUID to, double amount, String reason) {
        this.timestamp = System.currentTimeMillis();
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.reason = reason;
    }
}
