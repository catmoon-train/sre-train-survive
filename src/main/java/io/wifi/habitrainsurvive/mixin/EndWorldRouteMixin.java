package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.world.*;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EndPortalBlock.class)
public abstract class EndWorldRouteMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void habi$pairedEnd(ServerLevel source, Entity entity, BlockPos entrance, CallbackInfoReturnable<DimensionTransition> cir) {
        WorldManager manager = WorldManager.get(); WorldDefinition d = manager.definition(source.dimension().location().toString());
        if (d == null) return;
        if (!d.portals) { cir.setReturnValue(null); return; }
        if (!d.managed()) return;
        String id = d.preset.equals("end") ? d.overworld : d.end;
        ServerLevel target = manager.level(id);
        if (target == null || entity instanceof ServerPlayer p && !manager.guard(p, target)) { cir.setReturnValue(null); return; }
        WorldDefinition dest = manager.definition(id);
        if (!(entity instanceof ServerPlayer) && dest != null && (!dest.open || !dest.permission.isEmpty() || dest.entryFee > 0 || manager.runtime(id).pendingReset)) { cir.setReturnValue(null); return; }
        GlobalPosInfo configured = manager.spawn(id); BlockPos feet;
        if (d.preset.equals("end")) {
            if (configured != null) feet = configured.toBlockPos();
            else { target.getChunk(0, 0); feet = SafeTeleportService.safeColumn(target, 0, 0); }
            if (feet == null || !SafeTeleportService.safe(target, feet)) { cir.setReturnValue(null); return; }
        } else {
            int x = Math.min(100, (int) target.getWorldBorder().getSize() / 2 - 5); feet = new BlockPos(x, 50, 0);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                target.setBlock(feet.offset(dx, -1, dz), Blocks.OBSIDIAN.defaultBlockState(), 3);
                for (int dy = 0; dy <= 2; dy++) target.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        cir.setReturnValue(new DimensionTransition(target, Vec3.atBottomCenterOf(feet), Vec3.ZERO, entity.getYRot(), entity.getXRot(), DimensionTransition.PLAY_PORTAL_SOUND.then(DimensionTransition.PLACE_PORTAL_TICKET)));
    }
}
