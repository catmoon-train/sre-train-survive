package io.wifi.habitrainsurvive.town;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class TownPlot {
    public String chunkKey; // dimension|chunkX|chunkZ
    public String name = "";
    public UUID ownerUuid;
    public String ownerName;
    public boolean forSale;
    public double salePrice;
    public Set<UUID> trusted = new HashSet<>();

    public TownPlot() {
    }

    public TownPlot(String chunkKey) {
        this.chunkKey = chunkKey;
        this.forSale = false;
        this.salePrice = 0.0;
    }

    public boolean isTrusted(UUID uuid) {
        return trusted != null && trusted.contains(uuid);
    }

    public void clearOwner() {
        this.ownerUuid = null;
        this.ownerName = null;
        this.forSale = false;
        this.salePrice = 0.0;
        if (this.trusted != null) {
            this.trusted.clear();
        }
    }
}
