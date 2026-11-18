package io.wifi.habitrainsurvive.town;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import net.minecraft.core.BlockPos;

import java.util.*;

public class Town {
    public String id;
    public String name;
    public UUID mayorUuid;
    public String mayorName;
    public String boardMessage = "欢迎来到我们的城镇！";
    public double bankBalance = 0.0;
    public double dailyTax = 10.0;
    public boolean openToPublic = false;
    public boolean pvpEnabled = false;
    public boolean explosionEnabled = false;
    public boolean fireSpreadEnabled = false;
    public boolean allowAllResidentBuild = true;
    public boolean publicInteract = false;
    public boolean friendlyFire = false;
    public GlobalPosInfo spawnPos;
    public Map<UUID, TownRank> residents = new HashMap<>();
    public Map<UUID, String> residentNames = new HashMap<>();
    public Map<String, TownPlot> claimedChunks = new HashMap<>(); // key: dim|chunkX|chunkZ
    public Set<UUID> pendingInvites = new HashSet<>();
    public Set<String> allies = new HashSet<>();
    public Set<UUID> outlaws = new HashSet<>();
    public Map<UUID, String> outlawNames = new HashMap<>();
    public int upkeepGraceDays = 0;
    public long createdAt;
    public long lastUpkeepTimestamp;

    public Town() {
    }

    public Town(String id, String name, UUID mayorUuid, String mayorName, GlobalPosInfo spawnPos) {
        this.id = id;
        this.name = name;
        this.mayorUuid = mayorUuid;
        this.mayorName = mayorName;
        this.spawnPos = spawnPos;
        this.residents.put(mayorUuid, TownRank.MAYOR);
        this.residentNames.put(mayorUuid, mayorName);
        this.createdAt = System.currentTimeMillis();
        this.lastUpkeepTimestamp = System.currentTimeMillis();
    }

    public static String chunkKey(String dim, int chunkX, int chunkZ) {
        return dim + "|" + chunkX + "|" + chunkZ;
    }

    public static String chunkKeyAt(String dim, BlockPos pos) {
        return chunkKey(dim, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public boolean isOutlaw(UUID uuid) {
        return outlaws != null && outlaws.contains(uuid);
    }

    public boolean isResident(UUID uuid) {
        return mayorUuid.equals(uuid) || (residents != null && residents.containsKey(uuid));
    }

    public TownRank getRank(UUID uuid) {
        if (mayorUuid.equals(uuid)) return TownRank.MAYOR;
        return residents != null ? residents.get(uuid) : null;
    }

    public boolean isStaff(UUID uuid) {
        TownRank r = getRank(uuid);
        return r != null && r.getLevel() >= TownRank.ASSISTANT.getLevel();
    }

    public String getResidentName(UUID uuid) {
        if (uuid == null) return "未知";
        if (uuid.equals(mayorUuid) && mayorName != null && !mayorName.isBlank()) return mayorName;
        if (residentNames != null && residentNames.containsKey(uuid)) return residentNames.get(uuid);
        return uuid.toString().substring(0, 8);
    }

    public void addResident(UUID uuid, String playerName, TownRank rank) {
        if (residents == null) residents = new HashMap<>();
        if (residentNames == null) residentNames = new HashMap<>();
        residents.put(uuid, rank);
        if (playerName != null && !playerName.isBlank()) {
            residentNames.put(uuid, playerName);
        }
    }

    public void removeResident(UUID uuid) {
        if (residents != null) residents.remove(uuid);
        if (residentNames != null) residentNames.remove(uuid);
        clearPlayerPlots(uuid);
    }

    public void clearPlayerPlots(UUID uuid) {
        if (claimedChunks == null) return;
        for (TownPlot plot : claimedChunks.values()) {
            if (uuid.equals(plot.ownerUuid)) {
                plot.clearOwner();
            } else if (plot.trusted != null) {
                plot.trusted.remove(uuid);
            }
        }
    }

    public void transferMayor(UUID newMayorUuid, String newMayorName) {
        UUID oldMayor = this.mayorUuid;
        if (residents == null) residents = new HashMap<>();
        if (residentNames == null) residentNames = new HashMap<>();
        if (oldMayor != null && !oldMayor.equals(newMayorUuid)) {
            residents.put(oldMayor, TownRank.CO_MAYOR);
        }
        this.mayorUuid = newMayorUuid;
        this.mayorName = newMayorName != null && !newMayorName.isBlank() ? newMayorName : getResidentName(newMayorUuid);
        residents.put(newMayorUuid, TownRank.MAYOR);
        residentNames.put(newMayorUuid, this.mayorName);
    }

    public int getMaxChunks(int baseMaxChunks, int chunksPerResident) {
        int count = residents != null ? residents.size() : 1;
        return Math.max(1, baseMaxChunks + count * chunksPerResident);
    }

    public boolean canBuild(String dim, UUID uuid, BlockPos pos) {
        if (isOutlaw(uuid)) return false;
        if (isStaff(uuid)) return true;
        TownPlot plot = claimedChunks != null ? claimedChunks.get(chunkKeyAt(dim, pos)) : null;
        if (plot != null && plot.ownerUuid != null) {
            return plot.ownerUuid.equals(uuid) || plot.isTrusted(uuid);
        }
        if (!isResident(uuid)) return false;
        return allowAllResidentBuild;
    }

    public boolean canBuild(UUID uuid, BlockPos pos) {
        if (isOutlaw(uuid)) return false;
        if (isStaff(uuid)) return true;
        if (claimedChunks != null) {
            for (Map.Entry<String, TownPlot> entry : claimedChunks.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                if (parts.length == 3) {
                    int cx = Integer.parseInt(parts[1]);
                    int cz = Integer.parseInt(parts[2]);
                    if ((pos.getX() >> 4) == cx && (pos.getZ() >> 4) == cz) {
                        TownPlot plot = entry.getValue();
                        if (plot.ownerUuid != null) {
                            return plot.ownerUuid.equals(uuid) || plot.isTrusted(uuid);
                        }
                        break;
                    }
                }
            }
        }
        if (!isResident(uuid)) return false;
        return allowAllResidentBuild;
    }

    public boolean canInteract(String dim, UUID uuid, BlockPos pos) {
        if (isOutlaw(uuid)) return false;
        if (isStaff(uuid)) return true;
        TownPlot plot = claimedChunks != null ? claimedChunks.get(chunkKeyAt(dim, pos)) : null;
        if (plot != null && plot.ownerUuid != null) {
            return plot.ownerUuid.equals(uuid) || plot.isTrusted(uuid);
        }
        if (isResident(uuid)) return true;
        return publicInteract;
    }
}
