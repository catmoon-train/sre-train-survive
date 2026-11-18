package io.wifi.habitrainsurvive.hub;

import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.config.CoreConfigManager;
import io.wifi.habitrainsurvive.menu.MenuManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import io.wifi.habitrainsurvive.world.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class HubManager {
    public static final class Config {
        public boolean autoBuild = true, firstJoinToHub = true, everyJoinToHub = false, fallbackRespawnToHub = true, voidRescue = true;
        public int blocksPerTick = 512;
        void validate() { if (blocksPerTick < 32 || blocksPerTick > 4096) throw new IllegalArgumentException("blocksPerTick 必须为32至4096"); }
    }
    private static final HubManager INSTANCE = new HubManager();
    private Config config = new Config();
    private Path file;
    private HubBuilder builder;
    private final Set<UUID> known = new HashSet<>();
    public static HubManager get() { return INSTANCE; }
    public void load(Path file, Set<UUID> existing) throws IOException {
        this.file = file; config = CoreConfigManager.load(file, Config.class, new Config(), Config::validate);
        known.clear(); known.addAll(existing); builder = new HubBuilder();
    }
    public void reload() throws IOException { config = CoreConfigManager.load(file, Config.class, new Config(), Config::validate); }
    public boolean fallbackRespawn() { return config.fallbackRespawnToHub; }
    public GlobalPosInfo target() {
        GlobalPosInfo legacy = CmiManager.get().getServerSpawn();
        return legacy != null && WorldManager.get().current(legacy) && legacy.resolveLevel(WorldManager.get().server()) != null
                ? legacy : WorldManager.get().spawn(WorldManager.HUB);
    }
    public void teleport(ServerPlayer player) {
        GlobalPosInfo target = target();
        if (target == null) { MessageUtil.sendError(player, "主城", "主城尚未就绪，请稍后重试"); return; }
        TeleportWarmupManager.get().scheduleTeleportToPos(player, "服务器主城", target);
    }
    public void rescue(ServerPlayer player) {
        GlobalPosInfo p = WorldManager.get().spawn(WorldManager.HUB);
        if (p == null || p.resolveLevel(player.server) == null) return;
        TeleportWarmupManager.get().cancel(player.getUUID());
        WorldManager.get().recover(() -> p.teleportAsync(player));
        player.resetFallDistance(); player.clearFire();
    }
    public void registerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer p = handler.getPlayer();
            if (!handler.isAcceptingMessages() || CmiManager.get().getOrCreateProfile(p).isBanned()) return;
            boolean first = known.add(p.getUUID());
            if (WorldManager.get().staleLogin(p)) rescue(p);
            else if (config.everyJoinToHub || first && config.firstJoinToHub) {
                GlobalPosInfo target = target();
                if (target != null && target.resolveLevel(server) != null)
                    WorldManager.get().recover(() -> target.teleportAsync(p));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TeleportWarmupManager.get().cancel(handler.getPlayer().getUUID()));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND || !world.dimension().location().toString().equals(WorldManager.HUB)) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            if (pos.getY() != 82) return InteractionResult.PASS;
            if (pos.getX() == 0 && pos.getZ() == -28) { WorldManager.get().teleport(p, "minecraft:overworld"); return InteractionResult.SUCCESS; }
            if (pos.getX() == 0 && pos.getZ() == 28) { MenuManager.get().open(p, "worlds"); return InteractionResult.SUCCESS; }
            if (pos.getX() == -28 && pos.getZ() == 0) { serverCommand(p, "shop"); return InteractionResult.SUCCESS; }
            if (pos.getX() == 28 && pos.getZ() == 0) { MenuManager.get().open(p, "main"); return InteractionResult.SUCCESS; }
            return InteractionResult.PASS;
        });
    }
    private void serverCommand(ServerPlayer player, String command) { player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command); }
    public void tick(MinecraftServer server) {
        if (builder != null && config.autoBuild) builder.tick(config.blocksPerTick);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (p.serverLevel().dimension().location().toString().equals(WorldManager.HUB)) {
            if (config.voidRescue && p.getY() < 65) rescue(p);
        }
    }
    public void rebuild() throws IOException {
        var r = WorldManager.get().runtime(WorldManager.HUB); r.hubBuilt = false; r.imported = false; r.hubBuildCursor = 0;
        WorldManager.get().saveStrict(); builder = new HubBuilder();
    }
}
