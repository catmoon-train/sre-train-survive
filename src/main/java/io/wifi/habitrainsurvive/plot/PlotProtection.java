package io.wifi.habitrainsurvive.plot;

import io.wifi.habitrainsurvive.claim.Claim;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Roads and unsold land have no claim: deny mutations there before ordinary claim checks run. */
public final class PlotProtection {
    private PlotProtection() { }
    private static boolean applies(Level level) { return ModuleConfig.isEnabled(ModuleId.PLOT) && PlotGrid.WORLD.equals(level.dimension().location().toString()); }
    private static boolean unowned(Level level, BlockPos pos) { return applies(level) && PlotManager.get().claimAt(PlotGrid.WORLD, pos) == null; }
    private static void deny(ServerPlayer player) { MessageUtil.sendError(player, "地皮保护", "道路和未购地皮禁止修改；使用 /plot 购买地皮。"); }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> {
            if (player instanceof ServerPlayer sp && !sp.hasPermissions(2) && unowned(level, pos)) { deny(sp); return false; }
            return true;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer sp && !sp.hasPermissions(2) && unowned(level, entity.blockPosition())) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || sp.hasPermissions(2) || !applies(level)) return InteractionResult.PASS;
            var held = sp.getItemInHand(hand);
            BlockPos clicked = hit.getBlockPos();
            BlockPos target = held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem ? clicked.relative(hit.getDirection()) : clicked;
            if (unowned(level, clicked) || unowned(level, target)) { deny(sp); return InteractionResult.FAIL; }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (player instanceof ServerPlayer sp && !sp.hasPermissions(2) && applies(level) && sp.getItemInHand(hand).getItem() instanceof BucketItem) {
                Claim claim = PlotManager.get().claimAt(PlotGrid.WORLD, sp.blockPosition());
                if (claim == null || !claim.canPlace(sp.getUUID())) return InteractionResultHolder.fail(sp.getItemInHand(hand));
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer sp && !sp.hasPermissions(2) && unowned(level, entity.blockPosition())) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!applies(entity.level()) || !(source.getEntity() instanceof ServerPlayer sp) || sp.hasPermissions(2)) return true;
            Claim claim = PlotManager.get().claimAt(PlotGrid.WORLD, entity.blockPosition());
            return claim != null && claim.canBreak(sp.getUUID());
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (!applies(level)) return;
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            for (int x = baseX; x < baseX + 16; x++) for (int z = baseZ; z < baseZ + 16; z++) {
                if (PlotGrid.at(x, z) != null) continue;
                int lx = Math.floorMod(x, PlotGrid.STRIDE), lz = Math.floorMod(z, PlotGrid.STRIDE);
                boolean curb = lx == PlotGrid.OFFSET - 1 || lx == PlotGrid.OFFSET + PlotGrid.SIZE || lz == PlotGrid.OFFSET - 1 || lz == PlotGrid.OFFSET + PlotGrid.SIZE;
                // Only surface roads are decorated; owned interiors and buildings are never regenerated.
                var state = (curb ? Blocks.STONE_BRICKS : Blocks.SMOOTH_STONE).defaultBlockState();
                BlockPos pos = new BlockPos(x, PlotGrid.SURFACE, z);
                if (!chunk.getBlockState(pos).equals(state)) chunk.setBlockState(pos, state, false);
            }
        });
    }
}
