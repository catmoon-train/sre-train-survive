package io.wifi.habitrainsurvive.cmi;

import java.util.ArrayList;
import java.util.List;

public class KitData {
    public String name;
    public long cooldownSeconds = 3600;
    public double cost = 0.0;
    public List<String> serializedItems = new ArrayList<>();

    public KitData() {
    }

    public KitData(String name, long cooldownSeconds, double cost, List<String> serializedItems) {
        this.name = name;
        this.cooldownSeconds = cooldownSeconds;
        this.cost = cost;
        this.serializedItems = serializedItems;
    }
}
