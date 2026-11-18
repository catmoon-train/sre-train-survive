package io.wifi.habitrainsurvive.cmi;

import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.security.CombatTagManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CmiManager {
    private static final CmiManager INSTANCE = new CmiManager();

    public static class Storage {
        public int maxHomesPerPlayer = 5;
        public long rtpCooldownSeconds = 60;
        public long tpaRequestCooldownSeconds = 10;
        public double rtpCost = 20.0;
        public GlobalPosInfo serverSpawn = null;
        public Map<String, WarpPoint> warps = new ConcurrentHashMap<>();
        public Map<String, GlobalPosInfo> jails = new ConcurrentHashMap<>();
        public Map<String, KitData> kits = new ConcurrentHashMap<>();
        public Map<String, Double> worthTable = new ConcurrentHashMap<>(); // itemId -> pricePerUnit
        public Map<UUID, Double> bounties = new ConcurrentHashMap<>();   // targetUuid -> totalBounty
        public Map<UUID, PlayerProfileData> profiles = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();
    private final Map<UUID, TpaRequest> incomingTpa = new ConcurrentHashMap<>();
    private final Map<UUID, Long> tpaSendAfter = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastPrivateMsgPartner = new ConcurrentHashMap<>();

    public static CmiManager get() {
        return INSTANCE;
    }

    public void load(Path path) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.warps == null) this.storage.warps = new ConcurrentHashMap<>();
        if (this.storage.jails == null) this.storage.jails = new ConcurrentHashMap<>();
        if (this.storage.kits == null) this.storage.kits = new ConcurrentHashMap<>();
        if (this.storage.worthTable == null) this.storage.worthTable = new ConcurrentHashMap<>();
        if (this.storage.bounties == null) this.storage.bounties = new ConcurrentHashMap<>();
        if (this.storage.profiles == null) this.storage.profiles = new ConcurrentHashMap<>();
        for (PlayerProfileData profile : storage.profiles.values()) {
            HomeSlotUpgrades.validate(profile.purchasedHomeSlots);
            if (profile.homes == null) profile.homes = new HashMap<>();
        }
        ensureDefaultWorthTable();
    }

    private void ensureDefaultWorthTable() {
        storage.worthTable.putIfAbsent("minecraft:coal", 2.0);
        storage.worthTable.putIfAbsent("minecraft:copper_ingot", 3.0);
        storage.worthTable.putIfAbsent("minecraft:iron_ingot", 8.0);
        storage.worthTable.putIfAbsent("minecraft:gold_ingot", 15.0);
        storage.worthTable.putIfAbsent("minecraft:redstone", 2.5);
        storage.worthTable.putIfAbsent("minecraft:lapis_lazuli", 3.0);
        storage.worthTable.putIfAbsent("minecraft:emerald", 35.0);
        storage.worthTable.putIfAbsent("minecraft:diamond", 80.0);
        storage.worthTable.putIfAbsent("minecraft:netherite_ingot", 450.0);
        storage.worthTable.putIfAbsent("minecraft:wheat", 1.2);
        storage.worthTable.putIfAbsent("minecraft:carrot", 1.0);
        storage.worthTable.putIfAbsent("minecraft:potato", 1.0);
        storage.worthTable.putIfAbsent("minecraft:melon_slice", 0.5);
        storage.worthTable.putIfAbsent("minecraft:pumpkin", 2.0);
        storage.worthTable.putIfAbsent("minecraft:sugar_cane", 1.0);
        storage.worthTable.putIfAbsent("minecraft:oak_log", 1.5);
        storage.worthTable.putIfAbsent("minecraft:cobblestone", 0.2);
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    public void registerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (ModuleConfig.isEnabled(ModuleId.TOWN)) {
                io.wifi.habitrainsurvive.town.TownManager.get().onPlayerJoin(player);
            }
            if (!ModuleConfig.isEnabled(ModuleId.CMI)) return;
            PlayerProfileData profile = getOrCreateProfile(player);
            profile.lastKnownName = player.getGameProfile().getName();
            profile.lastSeenTimestamp = System.currentTimeMillis();

            // 封禁拦截
            if (profile.isBanned()) {
                long remMin = Math.max(1L, (profile.bannedUntil - System.currentTimeMillis()) / 60_000L);
                player.connection.disconnect(Component.literal("§c您已被服务器封禁！\n§e原因: §f" + profile.banReason + "\n§e剩余解封时间: §b" + remMin + " 分钟"));
                return;
            }

            // 昵称恢复
            if (profile.nickname != null && !profile.nickname.isBlank()) {
                player.setCustomName(Component.literal(profile.nickname));
                player.setCustomNameVisible(true);
            }

            // 离线邮件提醒
            if (profile.mailbox != null && !profile.mailbox.isEmpty()) {
                MessageUtil.sendInfo(player, "信箱提醒", "您有 " + profile.mailbox.size() + " 封未读离线邮件！请输入 /mail read 查看。");
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (!ModuleConfig.isEnabled(ModuleId.CMI)) return;
            ServerPlayer player = handler.getPlayer();
            PlayerProfileData profile = getOrCreateProfile(player);
            profile.lastSeenTimestamp = System.currentTimeMillis();
            profile.lastLocation = GlobalPosInfo.fromPlayer(player);

            // 战斗中强退惩罚 (Combat Log)
            if (CombatTagManager.get().isInCombat(player)) {
                CombatTagManager.get().clearCombat(player.getUUID());
                player.setHealth(0.0f);
                server.getPlayerList().broadcastSystemMessage(
                        MessageUtil.prefix("战斗监控", ChatFormatting.RED)
                                .append(Component.literal("玩家 " + player.getName().getString() + " 在战斗锁定期间强行退服逃跑，已受到死亡惩罚！").withStyle(ChatFormatting.YELLOW)),
                        false
                );
            }
        });

        // 玩家伤害拦截 (God Mode / 冻结保护)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!ModuleConfig.isEnabled(ModuleId.CMI)) return true;
            if (entity instanceof ServerPlayer sp) {
                PlayerProfileData profile = getOrCreateProfile(sp);
                if (profile.godMode || profile.frozen || profile.isJailed()) {
                    return false;
                }
            }
            return true;
        });

        // 死亡记录 (/back) 与 悬赏金结算 (Bounty)
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!ModuleConfig.isEnabled(ModuleId.CMI)) return;
            if (entity instanceof ServerPlayer victim) {
                CombatTagManager.get().clearCombat(victim.getUUID());
                PlayerProfileData profile = getOrCreateProfile(victim);
                profile.lastLocation = GlobalPosInfo.fromPlayer(victim);
                MessageUtil.sendInfo(victim, "CMI", "已记录您的死亡地点，输入 /back 可直接返回死亡位置。");

                // 悬赏金结算
                if (damageSource.getEntity() instanceof ServerPlayer killer && !killer.getUUID().equals(victim.getUUID())) {
                    Double bounty = storage.bounties.remove(victim.getUUID());
                    if (bounty != null && bounty > 0) {
                        EconomyManager.get().deposit(killer.getUUID(), bounty, "Bounty Claim on " + victim.getName().getString());
                        victim.server.getPlayerList().broadcastSystemMessage(
                                MessageUtil.prefix("悬赏通缉", ChatFormatting.GOLD)
                                        .append(Component.literal("猎人 " + killer.getName().getString() + " 击败了通缉犯 "
                                                + victim.getName().getString() + "，成功斩获全额赏金 " + EconomyManager.get().format(bounty) + "！").withStyle(ChatFormatting.YELLOW)),
                                false
                        );
                    }
                }
            }
        });

        // 聊天禁言拦截
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!ModuleConfig.isEnabled(ModuleId.CMI)) return true;
            PlayerProfileData profile = getOrCreateProfile(sender);
            if (profile.isMuted()) {
                long remainSec = (profile.mutedUntil - System.currentTimeMillis()) / 1000L;
                MessageUtil.sendError(sender, "禁言", "您已被禁言，剩余时间: " + remainSec + " 秒。原因: " + profile.muteReason);
                return false;
            }
            return true;
        });
    }

    public void tick(MinecraftServer server) {
        incomingTpa.entrySet().removeIf(entry -> entry.getValue().isExpired());
        long now = System.currentTimeMillis();
        tpaSendAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
        TeleportWarmupManager.get().tickSecond(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerProfileData prof = getOrCreateProfile(player);
            prof.totalPlaytimeSeconds++;

            // 隐身状态维持
            if (prof.vanished) {
                player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false, false));
            }

            // 冻结状态防移动
            if (prof.frozen && prof.lastLocation != null) {
                if (player.position().distanceToSqr(prof.lastLocation.x, prof.lastLocation.y, prof.lastLocation.z) > 0.25) {
                    io.wifi.habitrainsurvive.world.WorldManager.get().recover(() -> prof.lastLocation.teleportAsync(player));
                    MessageUtil.sendError(player, "安全冻结", "您正处于管理员冻结审查状态，请勿移动！");
                }
            }

            // 监狱关押状态检查与到期自动释放
            if (prof.jailedUntil > 0L) {
                if (System.currentTimeMillis() >= prof.jailedUntil) {
                    prof.jailedUntil = 0L;
                    prof.jailName = "";
                    prof.jailReason = "";
                    if (prof.preJailLocation != null) {
                        io.wifi.habitrainsurvive.world.WorldManager.get().recover(() -> prof.preJailLocation.teleportAsync(player));
                        prof.preJailLocation = null;
                    }
                    MessageUtil.sendSuccess(player, "监狱系统", "您的刑期已满，现已获释，正在准备返回原处。");
                } else {
                    GlobalPosInfo jailPos = storage.jails.get(prof.jailName.toLowerCase(Locale.ROOT));
                    if (jailPos != null && player.position().distanceToSqr(jailPos.x, jailPos.y, jailPos.z) > 25.0) {
                        io.wifi.habitrainsurvive.world.WorldManager.get().recover(() -> jailPos.teleportAsync(player));
                        long leftSec = (prof.jailedUntil - System.currentTimeMillis()) / 1000L;
                        MessageUtil.sendError(player, "监狱系统", "您正在服刑中，无法离开监狱！剩余时间: " + leftSec + " 秒。");
                    }
                }
            }
        }
    }

    public PlayerProfileData getOrCreateProfile(ServerPlayer player) {
        return storage.profiles.computeIfAbsent(player.getUUID(), id -> new PlayerProfileData(id, player.getGameProfile().getName()));
    }

    public PlayerProfileData getOrCreateProfile(UUID uuid, String name) {
        return storage.profiles.computeIfAbsent(uuid, id -> new PlayerProfileData(id, name));
    }

    public Optional<PlayerProfileData> findProfileByName(String name) {
        return storage.profiles.values().stream()
                .filter(p -> p.lastKnownName != null && p.lastKnownName.equalsIgnoreCase(name))
                .findFirst();
    }

    public void recordLastLocation(ServerPlayer player) {
        getOrCreateProfile(player).lastLocation = GlobalPosInfo.fromPlayer(player);
    }

    public Set<UUID> knownPlayers() { return Set.copyOf(storage.profiles.keySet()); }

    public void cancelAllTpa() { incomingTpa.clear(); }

    public GlobalPosInfo getServerSpawn() {
        return storage.serverSpawn;
    }

    public void setServerSpawn(GlobalPosInfo pos) {
        storage.serverSpawn = pos;
    }

    public int getBaseMaxHomesForPlayer(UUID uuid) {
        int groupMax = PermissionManager.get().getPlayerGroup(uuid).maxHomes;
        return Math.max(0, Math.max(storage.maxHomesPerPlayer, groupMax));
    }

    public int getPurchasedHomeSlots(UUID uuid) {
        PlayerProfileData profile = storage.profiles.get(uuid);
        return profile == null ? 0 : profile.purchasedHomeSlots;
    }

    public int getMaxHomesForPlayer(UUID uuid) {
        return HomeSlotUpgrades.maximum(getBaseMaxHomesForPlayer(uuid), getPurchasedHomeSlots(uuid));
    }

    public long nextHomeSlotPrice(UUID uuid) { return HomeSlotUpgrades.nextPrice(getPurchasedHomeSlots(uuid)); }

    public synchronized boolean purchaseHomeSlot(ServerPlayer player, int quotedPurchased, long quotedPrice) {
        PlayerProfileData profile = getOrCreateProfile(player);
        if (!player.isAlive() || player.isSpectator()) {
            MessageUtil.sendError(player, "家额度", "请在存活且非旁观状态购买。"); return false;
        }
        if (profile.purchasedHomeSlots != quotedPurchased || getMaxHomesForPlayer(player.getUUID()) == Integer.MAX_VALUE) {
            MessageUtil.sendError(player, "家额度", "报价已失效或额度已达上限，请重新确认。"); return false;
        }
        long cost = nextHomeSlotPrice(player.getUUID());
        if (cost != quotedPrice) {
            MessageUtil.sendError(player, "家额度", "购买价格已变化，请重新确认。"); return false;
        }
        double balance = EconomyManager.get().getBalance(player.getUUID());
        if (!Double.isFinite(balance) || balance < 0 || balance > 1e12
                || !EconomyManager.get().withdraw(player.getUUID(), cost, "Buy home slot #" + (profile.purchasedHomeSlots + 1))) {
            MessageUtil.sendError(player, "家额度", "金币不足或余额无效，本次需要 " + EconomyManager.get().format(cost)); return false;
        }
        profile.purchasedHomeSlots++;
        ServerDataManager.saveAll();
        MessageUtil.sendSuccess(player, "家额度", "购买成功，家上限已增加1个，当前上限为 " + getMaxHomesForPlayer(player.getUUID()));
        return true;
    }

    public long getRtpCooldownForPlayer(UUID uuid) {
        return PermissionManager.get().getPlayerGroup(uuid).rtpCooldownSeconds;
    }

    public double getRtpCost() {
        return storage.rtpCost;
    }

    public Map<String, WarpPoint> getWarps() {
        return storage.warps;
    }

    public Map<String, GlobalPosInfo> getJails() {
        return storage.jails;
    }

    public Map<String, KitData> getKits() {
        return storage.kits;
    }

    public Map<String, Double> getWorthTable() {
        return storage.worthTable;
    }

    public double getItemWorth(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return storage.worthTable.getOrDefault(id.toString(), 0.0);
    }

    public void setItemWorth(Item item, double price) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (price <= 0) {
            storage.worthTable.remove(id.toString());
        } else {
            storage.worthTable.put(id.toString(), price);
        }
    }

    public Map<UUID, Double> getBounties() {
        return storage.bounties;
    }

    public long getTpaCooldownRemaining(UUID sender) {
        long remaining = tpaSendAfter.getOrDefault(sender, 0L) - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    public void markTpaSent(UUID sender) {
        long seconds = Math.max(0, Math.min(3600, storage.tpaRequestCooldownSeconds));
        if (seconds > 0) tpaSendAfter.put(sender, System.currentTimeMillis() + seconds * 1000);
    }

    public void addTpaRequest(TpaRequest req) {
        incomingTpa.put(req.target, req);
    }

    public TpaRequest getTpaRequest(UUID target) {
        TpaRequest req = incomingTpa.get(target);
        if (req != null && req.isExpired()) {
            incomingTpa.remove(target);
            return null;
        }
        return req;
    }

    public TpaRequest removeTpaRequest(UUID target) {
        return incomingTpa.remove(target);
    }

    public boolean cancelOutgoingTpa(UUID sender) {
        return incomingTpa.entrySet().removeIf(e -> e.getValue().sender.equals(sender));
    }

    public void setLastMsgPartner(UUID p1, UUID p2) {
        lastPrivateMsgPartner.put(p1, p2);
        lastPrivateMsgPartner.put(p2, p1);
    }

    public UUID getLastMsgPartner(UUID player) {
        return lastPrivateMsgPartner.get(player);
    }
}
