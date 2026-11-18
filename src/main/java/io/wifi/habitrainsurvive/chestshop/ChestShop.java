package io.wifi.habitrainsurvive.chestshop;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ChestShop {
    public String id;
    public UUID ownerUuid;
    public String ownerName;
    public String dimension;
    public int x, y, z;
    public String serializedItem; // 单件模板物品 JSON
    public String itemDisplayName;
    public double buyPrice;  // 顾客从商店购买单价 (<=0 表示不出售)
    public double sellPrice; // 顾客向商店出售回收单价 (<=0 表示不收购)
    public boolean adminInfinite = false;
    public Set<UUID> staff = new HashSet<>();
    public String containerType; // Registry ID; old shops are bound on their first valid local access.
    public boolean identityBound; // A persisted custom-data marker identifies the original block entity.
    public long createdAt;

    public ChestShop() {
    }

    public ChestShop(String id, UUID ownerUuid, String ownerName, String dimension,
                     int x, int y, int z, String serializedItem, String itemDisplayName,
                     double buyPrice, double sellPrice) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.serializedItem = serializedItem;
        this.itemDisplayName = itemDisplayName;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.createdAt = System.currentTimeMillis();
    }

    public static String posKey(String dim, int x, int y, int z) {
        return dim + "|" + x + "|" + y + "|" + z;
    }

    public String getPosKey() {
        return posKey(dimension, x, y, z);
    }

    public boolean canManage(UUID uuid) {
        return uuid != null && (uuid.equals(ownerUuid) || (staff != null && staff.contains(uuid)));
    }
}
