package io.wifi.habitrainsurvive.town;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TownManager {
    private static final TownManager INSTANCE = new TownManager();

    public static class Storage {
        public double townCreateCost = 2500.0;
        public double chunkClaimCost = 200.0;
        public double dailyUpkeepPerChunk = 15.0;
        public int baseMaxChunks = 16;
        public int chunksPerResident = 8;
        public Map<String, Town> towns = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();
    private final Map<UUID, String> currentPlayerTownId = new ConcurrentHashMap<>();

    public static TownManager get() {
        return INSTANCE;
    }

    public void load(Path path) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.towns == null) {
            this.storage.towns = new ConcurrentHashMap<>();
        }
        if (this.storage.baseMaxChunks <= 0) this.storage.baseMaxChunks = 16;
        if (this.storage.chunksPerResident < 0) this.storage.chunksPerResident = 8;
        for (Town t : this.storage.towns.values()) {
            if (t.residents == null) t.residents = new HashMap<>();
            if (t.residentNames == null) t.residentNames = new HashMap<>();
            if (t.claimedChunks == null) t.claimedChunks = new HashMap<>();
            if (t.pendingInvites == null) t.pendingInvites = new HashSet<>();
            if (t.allies == null) t.allies = new HashSet<>();
            if (t.outlaws == null) t.outlaws = new HashSet<>();
            if (t.outlawNames == null) t.outlawNames = new HashMap<>();
            if (t.mayorUuid != null && t.mayorName != null) {
                t.residentNames.putIfAbsent(t.mayorUuid, t.mayorName);
            }
        }
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    public double getTownCreateCost() {
        return storage.townCreateCost;
    }

    public void setTownCreateCost(double cost) {
        storage.townCreateCost = Math.max(0.0, cost);
    }

    public double getChunkClaimCost() {
        return storage.chunkClaimCost;
    }

    public void setChunkClaimCost(double cost) {
        storage.chunkClaimCost = Math.max(0.0, cost);
    }

    public double getDailyUpkeepPerChunk() {
        return storage.dailyUpkeepPerChunk;
    }

    public void setDailyUpkeepPerChunk(double upkeep) {
        storage.dailyUpkeepPerChunk = Math.max(0.0, upkeep);
    }

    public int getBaseMaxChunks() {
        return storage.baseMaxChunks;
    }

    public int getChunksPerResident() {
        return storage.chunksPerResident;
    }

    public int getMaxChunksFor(Town town) {
        return town.getMaxChunks(storage.baseMaxChunks, storage.chunksPerResident);
    }

    public Town getTownByName(String name) {
        if (name == null) return null;
        return storage.towns.get(name.toLowerCase(Locale.ROOT));
    }

    public Town getPlayerTown(UUID playerUuid) {
        for (Town t : storage.towns.values()) {
            if (t.isResident(playerUuid)) {
                return t;
            }
        }
        return null;
    }

    public Town getTownAt(String dim, BlockPos pos) {
        String key = Town.chunkKeyAt(dim, pos);
        for (Town t : storage.towns.values()) {
            if (t.claimedChunks != null && t.claimedChunks.containsKey(key)) {
                return t;
            }
        }
        return null;
    }

    public Collection<Town> getAllTowns() {
        return storage.towns.values();
    }

    public void registerTown(Town town) {
        storage.towns.put(town.id.toLowerCase(Locale.ROOT), town);
    }

    public Town removeTown(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Town removed = storage.towns.remove(key);
        if (removed != null) {
            for (Town other : storage.towns.values()) {
                if (other.allies != null) {
                    other.allies.remove(removed.id);
                    other.allies.remove(key);
                }
            }
        }
        return removed;
    }

    public boolean areFriendly(UUID a, UUID b) {
        if (a.equals(b)) return true;
        Town ta = getPlayerTown(a);
        Town tb = getPlayerTown(b);
        if (ta == null || tb == null) return false;
        if (ta.id.equals(tb.id)) return true;
        return ta.allies != null && ta.allies.contains(tb.id);
    }

    public UUID resolveResidentUuid(Town town, String nameOrUuid, MinecraftServer server) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) return null;
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayerByName(nameOrUuid);
            if (online != null && town.isResident(online.getUUID())) {
                return online.getUUID();
            }
        }
        for (Map.Entry<UUID, String> entry : town.residentNames.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(nameOrUuid) && town.isResident(entry.getKey())) {
                return entry.getKey();
            }
        }
        try {
            UUID parsed = UUID.fromString(nameOrUuid);
            if (town.isResident(parsed)) return parsed;
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    public void onPlayerJoin(ServerPlayer player) {
        Town t = getPlayerTown(player.getUUID());
        if (t != null) {
            String currentName = player.getGameProfile().getName();
            t.residentNames.put(player.getUUID(), currentName);
            if (t.mayorUuid.equals(player.getUUID())) {
                t.mayorName = currentName;
            }
            if (t.upkeepGraceDays > 0 && t.isStaff(player.getUUID())) {
                double needed = Math.max(1, t.claimedChunks.size()) * storage.dailyUpkeepPerChunk;
                MessageUtil.sendError(player, "城镇财务", "城镇 [" + t.name + "] 已连续 " + t.upkeepGraceDays
                        + " 天未能足额缴纳维护费（每日需 " + EconomyManager.get().format(needed) + "），请尽快使用 /town deposit 补充金库！");
            }
        }
    }

    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        long dayMillis = 24L * 3600L * 1000L;
        for (Town t : storage.towns.values()) {
            if (now - t.lastUpkeepTimestamp >= dayMillis) {
                t.lastUpkeepTimestamp = now;
                double taxCollected = 0.0;
                if (t.dailyTax > 0) {
                    for (UUID resId : t.residents.keySet()) {
                        if (!resId.equals(t.mayorUuid)) {
                            if (EconomyManager.get().withdraw(resId, t.dailyTax, "Town Tax: " + t.name)) {
                                t.bankBalance += t.dailyTax;
                                taxCollected += t.dailyTax;
                            }
                        }
                    }
                }
                double upkeep = Math.max(1, t.claimedChunks.size()) * storage.dailyUpkeepPerChunk;
                boolean paid = t.bankBalance >= upkeep;
                if (paid) {
                    t.bankBalance -= upkeep;
                    t.upkeepGraceDays = 0;
                } else {
                    t.bankBalance = 0.0;
                    t.upkeepGraceDays++;
                }
                for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                    if (t.isResident(online.getUUID())) {
                        if (paid) {
                            MessageUtil.sendInfo(online, "城镇日结", "城镇 [" + t.name + "] 今日税收 +"
                                    + EconomyManager.get().format(taxCollected) + "，已扣除维护费 "
                                    + EconomyManager.get().format(upkeep) + "，金库剩余 "
                                    + EconomyManager.get().format(t.bankBalance) + "。");
                        } else if (t.isStaff(online.getUUID())) {
                            MessageUtil.sendError(online, "城镇财务", "城镇 [" + t.name + "] 金库不足以支付今日维护费 "
                                    + EconomyManager.get().format(upkeep) + "（已欠费第 " + t.upkeepGraceDays + " 天），请尽快捐献！");
                        }
                    }
                }
            }
        }

        // 城镇进出提示
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String dim = player.serverLevel().dimension().location().toString();
            Town current = getTownAt(dim, player.blockPosition());
            String prevId = currentPlayerTownId.getOrDefault(player.getUUID(), "");
            String curId = current != null ? current.id : "";
            if (!Objects.equals(prevId, curId)) {
                currentPlayerTownId.put(player.getUUID(), curId);
                if (current != null) {
                    if (current.isOutlaw(player.getUUID())) {
                        player.displayClientMessage(Component.literal("⚠ 警告：您是城镇 [" + current.name + "] 的通缉人员，已被禁止建造与交互！")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
                    } else {
                        String board = current.boardMessage == null ? "" : current.boardMessage;
                        player.displayClientMessage(Component.literal("⚑ 进入城镇 [" + current.name + "]")
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                                .append(board.isBlank() ? Component.empty() : Component.literal(" - " + board).withStyle(ChatFormatting.GRAY)), true);
                    }
                } else if (!prevId.isEmpty()) {
                    Town old = storage.towns.get(prevId.toLowerCase(Locale.ROOT));
                    String oldName = old != null ? old.name : prevId;
                    player.displayClientMessage(Component.literal("已离开城镇 [" + oldName + "]").withStyle(ChatFormatting.GRAY), true);
                }
            }
        }
    }
}
