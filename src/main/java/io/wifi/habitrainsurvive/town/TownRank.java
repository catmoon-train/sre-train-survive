package io.wifi.habitrainsurvive.town;

public enum TownRank {
    MAYOR(3, "镇长"),
    CO_MAYOR(2, "副镇长"),
    ASSISTANT(1, "城镇助理"),
    RESIDENT(0, "居民");

    private final int level;
    private final String displayName;

    TownRank(int level, String displayName) {
        this.level = level;
        this.displayName = displayName;
    }

    public int getLevel() {
        return level;
    }

    public String getDisplayName() {
        return displayName;
    }
}
