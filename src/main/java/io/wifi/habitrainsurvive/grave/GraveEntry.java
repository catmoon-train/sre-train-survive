package io.wifi.habitrainsurvive.grave;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;

import java.util.ArrayList;
import java.util.List;

public class GraveEntry {
    public String id;
    public GlobalPosInfo deathPos;
    public long timestamp;
    public double redeemFee;
    public List<String> serializedItems = new ArrayList<>();

    public GraveEntry() {
    }

    public GraveEntry(String id, GlobalPosInfo deathPos, double redeemFee, List<String> serializedItems) {
        this.id = id;
        this.deathPos = deathPos;
        this.timestamp = System.currentTimeMillis();
        this.redeemFee = redeemFee;
        this.serializedItems = serializedItems;
    }
}
