package io.wifi.habitrainsurvive.cmi;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;

public class WarpPoint {
    public String name;
    public GlobalPosInfo pos;
    public double cost;
    public String creator;
    public String description = "";
    public String icon = "minecraft:ender_pearl";
    public boolean enabled = true;

    public WarpPoint() {
    }

    public WarpPoint(String name, GlobalPosInfo pos, double cost, String creator) {
        this.name = name;
        this.pos = pos;
        this.cost = cost;
        this.creator = creator;
    }
}
