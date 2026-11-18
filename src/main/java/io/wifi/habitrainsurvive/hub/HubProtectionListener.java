package io.wifi.habitrainsurvive.hub;

import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import io.wifi.habitrainsurvive.world.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import java.util.*;

/** World-level policy is evaluated before claim/town permissions. */
public final class HubProtectionListener {
    private static final Map<UUID, Long> messages = new HashMap<>();
    private HubProtectionListener() {}
    public static WorldDefinition policy(Level level) { return WorldManager.get().definition(level.dimension().location().toString()); }
    public static boolean bypass(ServerPlayer player) { return PermissionManager.get().hasPermission(player, "habi.hub.build"); }
    public static boolean buildDenied(Level level, Entity actor) {
        WorldDefinition d = policy(level);
        return d != null && !d.build && (!(actor instanceof ServerPlayer p) || !bypass(p));
    }
    public static boolean pvpDenied(Level level) { WorldDefinition d = policy(level); return d != null && !d.pvp; }
    private static void denied(ServerPlayer p) {
        long now = System.currentTimeMillis();
        if (now - messages.getOrDefault(p.getUUID(), 0L) > 1500) {
            messages.put(p.getUUID(), now); MessageUtil.sendError(p, "世界保护", "此区域受服务器保护；请通过主城导航或 /menu 前往生存世界");
        }
    }
    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (player instanceof ServerPlayer p && buildDenied(world, p)) { denied(p); return false; } return true;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || bypass(p)) return InteractionResult.PASS;
            WorldDefinition d = policy(world);
            if (d != null && (!d.interact || !d.build && !p.getItemInHand(hand).isEmpty())) { denied(p); return InteractionResult.FAIL; }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer p && buildDenied(world, p)) { denied(p); return InteractionResultHolder.fail(p.getItemInHand(hand)); }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (player instanceof ServerPlayer p && (buildDenied(world, p) || entity instanceof ServerPlayer && pvpDenied(world))) {
                denied(p); return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            WorldDefinition d = policy(world);
            if (player instanceof ServerPlayer p && d != null && !d.interact && !bypass(p)) { denied(p); return InteractionResult.FAIL; }
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            WorldDefinition d = policy(entity.level());
            if (d != null && (!d.damage || !d.explosions && source.is(DamageTypeTags.IS_EXPLOSION))) return false;
            if (source.getEntity() instanceof ServerPlayer attacker && entity instanceof ServerPlayer
                    && (pvpDenied(entity.level()) || pvpDenied(attacker.level()))) return false;
            return !buildDenied(entity.level(), source.getEntity()) || entity instanceof ServerPlayer;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof ServerPlayer p && taken > 0) TeleportWarmupManager.get().cancel(p.getUUID());
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> messages.remove(handler.getPlayer().getUUID()));
    }
}
