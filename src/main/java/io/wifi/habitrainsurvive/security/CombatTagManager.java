package io.wifi.habitrainsurvive.security;

import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CombatTagManager {
    private static final CombatTagManager INSTANCE = new CombatTagManager();
    private static final long COMBAT_DURATION_MS = 15_000L;
    private final Map<UUID, Long> combatUntil = new ConcurrentHashMap<>();

    public static CombatTagManager get() {
        return INSTANCE;
    }

    public void tagCombat(ServerPlayer attacker, ServerPlayer victim) {
        long until = System.currentTimeMillis() + COMBAT_DURATION_MS;
        boolean attackerWasInCombat = isInCombat(attacker);
        boolean victimWasInCombat = isInCombat(victim);
        combatUntil.put(attacker.getUUID(), until);
        combatUntil.put(victim.getUUID(), until);
        if (!attackerWasInCombat) {
            MessageUtil.sendError(attacker, "战斗监控", "您已进入战斗状态 (15秒)！期间禁止传送、飞行及下线逃跑！");
        }
        if (!victimWasInCombat) {
            MessageUtil.sendError(victim, "战斗监控", "您已受到玩家攻击进入战斗状态 (15秒)！期间禁止传送与下线逃跑！");
        }
    }

    public boolean isInCombat(ServerPlayer player) {
        if (player.hasPermissions(2)) return false;
        Long until = combatUntil.get(player.getUUID());
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            combatUntil.remove(player.getUUID());
            return false;
        }
        return true;
    }

    public long getRemainingCombatSeconds(ServerPlayer player) {
        Long until = combatUntil.get(player.getUUID());
        if (until == null) return 0L;
        return Math.max(0L, (until - System.currentTimeMillis() + 999L) / 1000L);
    }

    public void clearCombat(UUID uuid) {
        combatUntil.remove(uuid);
    }
}
