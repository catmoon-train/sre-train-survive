package io.wifi.habitrainsurvive.permission;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PermissionManager {
    private static final PermissionManager INSTANCE = new PermissionManager();

    public static class RankGroup {
        public String name;
        public String prefix;
        public int maxHomes;
        public int maxClaims;
        public int maxAuctionListings;
        public int teleportWarmupSeconds;
        public int rtpCooldownSeconds;
        public Set<String> permissions = new HashSet<>();

        public RankGroup() {
        }

        public RankGroup(String name, String prefix, int maxHomes, int maxClaims, int maxAuctionListings,
                         int teleportWarmupSeconds, int rtpCooldownSeconds, Set<String> permissions) {
            this.name = name;
            this.prefix = prefix;
            this.maxHomes = maxHomes;
            this.maxClaims = maxClaims;
            this.maxAuctionListings = maxAuctionListings;
            this.teleportWarmupSeconds = teleportWarmupSeconds;
            this.rtpCooldownSeconds = rtpCooldownSeconds;
            this.permissions = new HashSet<>(permissions);
        }
    }

    public static class Storage {
        public Map<String, RankGroup> groups = new ConcurrentHashMap<>();
        public Map<UUID, String> playerGroups = new ConcurrentHashMap<>();
        public Map<UUID, Set<String>> playerExtraPerms = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();

    public static PermissionManager get() {
        return INSTANCE;
    }

    public void load(Path path) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.groups == null) this.storage.groups = new ConcurrentHashMap<>();
        if (this.storage.playerGroups == null) this.storage.playerGroups = new ConcurrentHashMap<>();
        if (this.storage.playerExtraPerms == null) this.storage.playerExtraPerms = new ConcurrentHashMap<>();
        ensureDefaultGroups();
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    private void ensureDefaultGroups() {
        storage.groups.putIfAbsent("default", new RankGroup("default", "§7[玩家]", 5, 5, 10, 3, 60,
                Set.of("habi.cmi.tpa", "habi.cmi.home", "habi.cmi.spawn", "habi.cmi.warp", "habi.cmi.rtp", "habi.cmi.back", "habi.cmi.daily")));
        storage.groups.putIfAbsent("vip", new RankGroup("vip", "§a[VIP]", 10, 10, 20, 1, 30,
                Set.of("habi.cmi.tpa", "habi.cmi.home", "habi.cmi.spawn", "habi.cmi.warp", "habi.cmi.rtp", "habi.cmi.back", "habi.cmi.daily", "habi.cmi.ec", "habi.cmi.wb", "habi.cmi.hat", "habi.cmi.nick")));
        storage.groups.putIfAbsent("svip", new RankGroup("svip", "§6[SVIP]", 20, 20, 40, 0, 10,
                Set.of("habi.cmi.*", "habi.claim.visualize")));
        storage.groups.putIfAbsent("admin", new RankGroup("admin", "§c[管理员]", 999, 999, 999, 0, 0,
                Set.of("*")));
    }

    public RankGroup getPlayerGroup(UUID uuid) {
        String gName = storage.playerGroups.getOrDefault(uuid, "default").toLowerCase(Locale.ROOT);
        return storage.groups.getOrDefault(gName, storage.groups.get("default"));
    }

    public boolean setPlayerGroup(UUID uuid, String groupName) {
        String key = groupName.toLowerCase(Locale.ROOT);
        if (!storage.groups.containsKey(key)) return false;
        storage.playerGroups.put(uuid, key);
        return true;
    }

    public void addPlayerPerm(UUID uuid, String node) {
        storage.playerExtraPerms.computeIfAbsent(uuid, k -> new HashSet<>()).add(node.toLowerCase(Locale.ROOT));
    }

    public void removePlayerPerm(UUID uuid, String node) {
        Set<String> set = storage.playerExtraPerms.get(uuid);
        if (set != null) set.remove(node.toLowerCase(Locale.ROOT));
    }

    public boolean hasPermission(ServerPlayer player, String node) {
        if (player.hasPermissions(2)) return true;
        String lower = node.toLowerCase(Locale.ROOT);
        Set<String> extra = storage.playerExtraPerms.getOrDefault(player.getUUID(), Collections.emptySet());
        if (checkNodeSet(extra, lower)) return true;
        RankGroup group = getPlayerGroup(player.getUUID());
        return group != null && checkNodeSet(group.permissions, lower);
    }

    public boolean hasPermission(CommandSourceStack source, String node, int fallbackOpLevel) {
        if (source.hasPermission(fallbackOpLevel)) return true;
        ServerPlayer player = source.getPlayer();
        return player != null && hasPermission(player, node);
    }

    private boolean checkNodeSet(Set<String> perms, String target) {
        if (perms == null) return false;
        if (perms.contains("*") || perms.contains(target)) return true;
        int dot = target.lastIndexOf('.');
        while (dot > 0) {
            String wildcard = target.substring(0, dot) + ".*";
            if (perms.contains(wildcard)) return true;
            dot = target.lastIndexOf('.', dot - 1);
        }
        return false;
    }

    public Map<String, RankGroup> getGroups() {
        return storage.groups;
    }
}
