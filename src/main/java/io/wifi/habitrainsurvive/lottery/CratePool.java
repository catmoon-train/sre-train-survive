package io.wifi.habitrainsurvive.lottery;

import io.wifi.habitrainsurvive.economy.ExperienceCost;
import java.util.ArrayList;
import java.util.List;

public class CratePool {
    public String id;
    public String displayName;
    public double singleDrawCost;
    public int pityThreshold = 50; // 保底抽数
    public ExperienceCost experienceDrawCost = new ExperienceCost();
    public List<CratePrize> prizes = new ArrayList<>();

    public CratePool() {
    }

    public CratePool(String id, String displayName, double singleDrawCost, int pityThreshold) {
        this.id = id;
        this.displayName = displayName;
        this.singleDrawCost = singleDrawCost;
        this.pityThreshold = pityThreshold;
    }

    public void migrate() {
        if (experienceDrawCost == null) experienceDrawCost = new ExperienceCost();
        experienceDrawCost.migrate();
    }

    public int getTotalWeight() {
        return prizes.stream().mapToInt(p -> Math.max(1, p.weight)).sum();
    }
}
