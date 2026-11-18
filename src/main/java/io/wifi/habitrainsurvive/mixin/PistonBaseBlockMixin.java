package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.claim.Claim;
import io.wifi.habitrainsurvive.claim.ClaimFlag;
import io.wifi.habitrainsurvive.claim.ClaimManager;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public class PistonBaseBlockMixin {
    @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
    private void habi$preventCrossClaimPistonGrief(Level level, BlockPos pos, Direction facing, boolean extending, CallbackInfoReturnable<Boolean> cir) {
        if (level.isClientSide()) return;
        String dim = level.dimension().location().toString();
        var worldPolicy = io.wifi.habitrainsurvive.hub.HubProtectionListener.policy(level);
        if (worldPolicy != null && !worldPolicy.pistons) { cir.setReturnValue(false); return; }
        if (!ModuleConfig.isEnabled(ModuleId.CLAIM)) return;
        Claim pistonClaim = ClaimManager.get().getClaimAt(dim, pos);
        for (int step = 1; step <= 13; step++) {
            BlockPos checkPos = pos.relative(facing, step);
            Claim targetClaim = ClaimManager.get().getClaimAt(dim, checkPos);
            if (targetClaim != null && !targetClaim.getFlag(ClaimFlag.PISTON)) {
                if (pistonClaim == null || !pistonClaim.id.equals(targetClaim.id)) {
                    cir.setReturnValue(false);
                    return;
                }
            }
        }
    }
}
