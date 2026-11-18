package io.wifi.habitrainsurvive.claim;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import net.minecraft.core.BlockPos;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Claim {
    public String id;
    public String name;
    public String parentClaimId = ""; // 若非空则为子领地
    public UUID ownerUuid;
    public String ownerName;
    public String dimension;
    public int minX, minY, minZ;
    public int maxX, maxY, maxZ;
    public GlobalPosInfo teleportPos;
    public String enterMessage;
    public String leaveMessage;
    public boolean forSale = false;
    public double salePrice = 0.0;
    public Map<UUID, ClaimRole> members = new HashMap<>();
    public Map<UUID, String> memberNames = new HashMap<>();
    public Map<UUID, ClaimRole> pendingInvites = new HashMap<>();
    public Map<ClaimFlag, Boolean> flags = new EnumMap<>(ClaimFlag.class);
    public long createdAt;

    public Claim() {
    }

    public Claim(String id, String name, UUID ownerUuid, String ownerName, String dimension,
                 BlockPos p1, BlockPos p2, GlobalPosInfo teleportPos) {
        this.id = id;
        this.name = name;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.dimension = dimension;
        this.minX = Math.min(p1.getX(), p2.getX());
        this.minY = Math.min(p1.getY(), p2.getY());
        this.minZ = Math.min(p1.getZ(), p2.getZ());
        this.maxX = Math.max(p1.getX(), p2.getX());
        this.maxY = Math.max(p1.getY(), p2.getY());
        this.maxZ = Math.max(p1.getZ(), p2.getZ());
        this.teleportPos = teleportPos;
        this.enterMessage = "§b欢迎进入 " + ownerName + " 的领地 [" + name + "]";
        this.leaveMessage = "§7已离开领地 [" + name + "]";
        for (ClaimFlag f : ClaimFlag.values()) {
            this.flags.put(f, f.getDefaultValue());
        }
        this.createdAt = System.currentTimeMillis();
    }

    public boolean isSubClaim() {
        return parentClaimId != null && !parentClaimId.isBlank();
    }

    public boolean contains(String dim, int x, int y, int z) {
        if (!this.dimension.equals(dim)) return false;
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean containsFully(String dim, BlockPos p1, BlockPos p2) {
        if (!this.dimension.equals(dim)) return false;
        int oMinX = Math.min(p1.getX(), p2.getX());
        int oMinY = Math.min(p1.getY(), p2.getY());
        int oMinZ = Math.min(p1.getZ(), p2.getZ());
        int oMaxX = Math.max(p1.getX(), p2.getX());
        int oMaxY = Math.max(p1.getY(), p2.getY());
        int oMaxZ = Math.max(p1.getZ(), p2.getZ());
        return oMinX >= minX && oMaxX <= maxX && oMinY >= minY && oMaxY <= maxY && oMinZ >= minZ && oMaxZ <= maxZ;
    }

    public boolean intersects(String dim, int oMinX, int oMinY, int oMinZ, int oMaxX, int oMaxY, int oMaxZ) {
        if (!this.dimension.equals(dim)) return false;
        return this.minX <= oMaxX && this.maxX >= oMinX
                && this.minY <= oMaxY && this.maxY >= oMinY
                && this.minZ <= oMaxZ && this.maxZ >= oMinZ;
    }

    public long getArea2D() {
        return (long) (maxX - minX + 1) * (long) (maxZ - minZ + 1);
    }

    public boolean getFlag(ClaimFlag flag) {
        if (flags == null) return flag.getDefaultValue();
        return flags.getOrDefault(flag, flag.getDefaultValue());
    }

    public ClaimRole getRole(UUID uuid) {
        if (ownerUuid.equals(uuid)) return ClaimRole.OWNER;
        if (members == null) return null;
        return members.get(uuid);
    }

    public boolean isTrustedMember(UUID uuid) {
        ClaimRole r = getRole(uuid);
        return r != null && r.getPower() >= ClaimRole.ACCESS_TRUST.getPower();
    }

    public boolean canBreak(UUID uuid) {
        ClaimRole r = getRole(uuid);
        if (r == ClaimRole.BANNED) return false;
        if (r != null && r.getPower() >= ClaimRole.TRUSTED.getPower()) return true;
        return getFlag(ClaimFlag.BLOCK_BREAK);
    }

    public boolean canPlace(UUID uuid) {
        ClaimRole r = getRole(uuid);
        if (r == ClaimRole.BANNED) return false;
        if (r != null && r.getPower() >= ClaimRole.TRUSTED.getPower()) return true;
        return getFlag(ClaimFlag.BLOCK_PLACE);
    }

    public boolean canOpenContainer(UUID uuid) {
        ClaimRole r = getRole(uuid);
        if (r == ClaimRole.BANNED) return false;
        if (r != null && r.getPower() >= ClaimRole.CONTAINER_TRUST.getPower()) return true;
        return getFlag(ClaimFlag.CONTAINER);
    }

    public boolean canInteract(UUID uuid) {
        ClaimRole r = getRole(uuid);
        if (r == ClaimRole.BANNED) return false;
        if (r != null && r.getPower() >= ClaimRole.ACCESS_TRUST.getPower()) return true;
        return getFlag(ClaimFlag.INTERACT);
    }

    public boolean canEnter(UUID uuid) {
        ClaimRole r = getRole(uuid);
        if (r == ClaimRole.BANNED) return false;
        if (r != null) return true;
        return getFlag(ClaimFlag.ENTER);
    }

    public boolean canPickupItem(UUID uuid) {
        ClaimRole r = getRole(uuid);
        if (r == ClaimRole.BANNED) return false;
        if (r != null && r.getPower() >= ClaimRole.ACCESS_TRUST.getPower()) return true;
        return getFlag(ClaimFlag.ITEM_PICKUP);
    }

    public boolean canUseEnderPearl(UUID uuid) {
        ClaimRole r = getRole(uuid);
        if (r == ClaimRole.BANNED) return false;
        if (r != null && r.getPower() >= ClaimRole.ACCESS_TRUST.getPower()) return true;
        return getFlag(ClaimFlag.ENDER_PEARL);
    }

    public boolean canFly(UUID uuid) {
        ClaimRole r = getRole(uuid);
        if (r == ClaimRole.BANNED) return false;
        if (r != null && r.getPower() >= ClaimRole.OWNER.getPower()) return getFlag(ClaimFlag.FLY);
        return r != null && r.getPower() >= ClaimRole.TRUSTED.getPower() && getFlag(ClaimFlag.FLY);
    }

    public boolean canManage(UUID uuid) {
        ClaimRole r = getRole(uuid);
        return r != null && r.getPower() >= ClaimRole.ADMIN.getPower();
    }
}
