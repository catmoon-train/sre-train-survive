package io.wifi.habitrainsurvive.world;

import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.DimensionType;

/** Nearby source portal -> corresponding coordinates; otherwise reuse bounded, paid safe RTP. */
public final class QuickDimensionTravel {
    private QuickDimensionTravel() { }

    public static boolean go(ServerPlayer player, boolean end) {
        WorldManager manager = WorldManager.get();
        WorldDefinition source = manager.definition(player.serverLevel().dimension().location().toString());
        String target = source == null ? (end ? "minecraft:the_end" : "minecraft:the_nether") : (end ? source.end : source.nether);
        return to(player, target);
    }

    public static boolean to(ServerPlayer player, String target) {
        WorldManager manager = WorldManager.get();
        WorldDefinition destination = manager.definition(target);
        if (destination == null || manager.level(target) == null) {
            MessageUtil.sendError(player, "维度旅行", "目标世界未加载"); return false;
        }
        if (player.serverLevel() == manager.level(target)) {
            MessageUtil.sendInfo(player, "维度旅行", "你已经在这个世界了"); return false;
        }
        String problem = manager.entryProblem(player, target, true);
        if (problem != null) { MessageUtil.sendError(player, "维度旅行", problem); return false; }
        boolean end = destination.preset.equals("end");
        if (!nearPortal(player, end)) return manager.rtp(player, target);
        long generation = manager.generation(target);
        TeleportWarmupManager.get().scheduleTeleport(player, destination.name, () -> {
            if (generation != manager.generation(target)) {
                MessageUtil.sendError(player, "维度旅行", "目标世界已重置，请重新发起传送"); return;
            }
            if (!nearPortal(player, end)) { manager.rtp(player, target); return; }
            // End travel keeps X/Z; Nether travel uses the actual dimension coordinate scales.
            double scale = end ? 1 : DimensionType.getTeleportationScale(player.serverLevel().dimensionType(), manager.level(target).dimensionType());
            GlobalPosInfo point = new GlobalPosInfo(target, player.getX() * scale, player.getY(), player.getZ() * scale, player.getYRot(), player.getXRot());
            SafeTeleportService.get().corresponding(player, point);
        });
        return true;
    }

    private static boolean nearPortal(ServerPlayer player, boolean end) {
        Block portal = end ? Blocks.END_PORTAL : Blocks.NETHER_PORTAL;
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-3, -3, -3), center.offset(3, 3, 3)))
            if (player.serverLevel().hasChunkAt(pos) && player.serverLevel().getBlockState(pos).is(portal)) return true;
        return false;
    }
}
