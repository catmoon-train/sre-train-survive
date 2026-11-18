package io.wifi.habitrainsurvive.cmi;

import java.util.UUID;

public class TpaRequest {
    public UUID sender;
    public UUID target;
    public boolean tpaHere; // true = target teleports to sender; false = sender teleports to target
    public long createdAt;

    public TpaRequest(UUID sender, UUID target, boolean tpaHere) {
        this.sender = sender;
        this.target = target;
        this.tpaHere = tpaHere;
        this.createdAt = System.currentTimeMillis();
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > 60_000L;
    }
}
