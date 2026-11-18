package io.wifi.habitrainsurvive.security;

import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.cmi.PlayerProfileData;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeleportWarmupManager {
    private static final TeleportWarmupManager INSTANCE = new TeleportWarmupManager();

    public static class PendingTeleport {
        public final UUID playerUuid;
        public final Vec3 startPos;
        public final float startHealth;
        public final String description;
        public final Runnable onComplete;
        public final String startDimension;
        public int remainingSeconds;

        public PendingTeleport(ServerPlayer player, int seconds, String description, Runnable onComplete) {
            this.playerUuid = player.getUUID();
            this.startPos = player.position();
            this.startHealth = player.getHealth();
            this.remainingSeconds = seconds;
            this.description = description;
            this.onComplete = onComplete;
            this.startDimension = player.serverLevel().dimension().location().toString();
        }
    }

    private final Map<UUID, PendingTeleport> pending = new ConcurrentHashMap<>();

    public static TeleportWarmupManager get() {
        return INSTANCE;
    }

    public void scheduleTeleport(ServerPlayer player, String description, Runnable teleportAction) {
        if (CombatTagManager.get().isInCombat(player)) {
            long sec = CombatTagManager.get().getRemainingCombatSeconds(player);
            MessageUtil.sendError(player, "传送拦截", "您正处于战斗状态中，请等待 " + sec + " 秒脱战后再传送！");
            MessageUtil.playError(player);
            return;
        }
        PlayerProfileData prof = CmiManager.get().getOrCreateProfile(player);
        if (prof.isJailed()) {
            MessageUtil.sendError(player, "监狱管控", "您正在服刑关押中，禁止使用任何传送功能！");
            return;
        }
        if (prof.frozen) {
            MessageUtil.sendError(player, "安全管控", "您已被管理员冻结，无法传送！");
            return;
        }

        int warmup = player.hasPermissions(2) ? 0 : PermissionManager.get().getPlayerGroup(player.getUUID()).teleportWarmupSeconds;
        if (warmup <= 0) {
            teleportAction.run();
            return;
        }

        pending.put(player.getUUID(), new PendingTeleport(player, warmup, description, () -> {
            teleportAction.run();
        }));
        MessageUtil.sendInfo(player, "传送读条", "即将传送至 [" + description + "]，请在 " + warmup + " 秒内保持静止不要移动！");
    }

    public void scheduleTeleportToPos(ServerPlayer player, String description, GlobalPosInfo targetPos) {
        scheduleTeleport(player, description, () -> {
            targetPos.teleportAsync(player, moved -> {
                MessageUtil.sendSuccess(moved, "传送", "已成功传送至 [" + description + "]！");
                MessageUtil.playDing(moved);
            });
        });
    }

    public void cancel(java.util.UUID uuid) {
        pending.remove(uuid);
        io.wifi.habitrainsurvive.world.SafeTeleportService.get().cancel(uuid);
    }

    public void cancelAll(String reason) {
        for (var uuid : java.util.List.copyOf(pending.keySet())) {
            ServerPlayer player = io.wifi.habitrainsurvive.data.ServerDataManager.getServer() == null ? null
                    : io.wifi.habitrainsurvive.data.ServerDataManager.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) MessageUtil.sendInfo(player, "传送取消", reason);
        }
        pending.clear();
    }

    public void tickSecond(MinecraftServer server) {
        Iterator<Map.Entry<UUID, PendingTeleport>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingTeleport> entry = it.next();
            PendingTeleport pt = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(pt.playerUuid);
            if (player == null) {
                it.remove();
                continue;
            }
            PlayerProfileData profile = CmiManager.get().getOrCreateProfile(player);
            if (!player.serverLevel().dimension().location().toString().equals(pt.startDimension) || profile.frozen || profile.isJailed()
                    || player.position().distanceToSqr(pt.startPos) > 0.15 || player.getHealth() < pt.startHealth || CombatTagManager.get().isInCombat(player)) {
                it.remove();
                MessageUtil.sendError(player, "传送打断", "检测到您发生移动或受到伤害，传送 [" + pt.description + "] 已取消！");
                MessageUtil.playError(player);
                continue;
            }
            pt.remainingSeconds--;
            player.serverLevel().sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 15, 0.3, 0.5, 0.3, 0.1);
            if (pt.remainingSeconds <= 0) {
                it.remove();
                pt.onComplete.run();
            } else {
                player.displayClientMessage(Component.literal("⏳ 正在传送至 [" + pt.description + "] ... 倒计时 " + pt.remainingSeconds + " 秒").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), true);
            }
        }
    }
}
