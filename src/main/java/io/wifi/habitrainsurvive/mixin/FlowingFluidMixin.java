package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.claim.Claim;
import io.wifi.habitrainsurvive.claim.ClaimFlag;
import io.wifi.habitrainsurvive.claim.ClaimManager;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
public class FlowingFluidMixin {
    @Inject(method = "canSpreadTo", at = @At("HEAD"), cancellable = true)
    private void habi$preventCrossClaimFluidGrief(BlockGetter level, BlockPos fromPos, BlockState fromBlockState,
                                                  Direction direction, BlockPos toPos, BlockState toBlockState,
                                                  FluidState toFluidState, Fluid fluid, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof Level realLevel && !realLevel.isClientSide()) {
            String dim = realLevel.dimension().location().toString();
            var worldPolicy = io.wifi.habitrainsurvive.hub.HubProtectionListener.policy(realLevel);
            if (worldPolicy != null && !worldPolicy.fluids) { cir.setReturnValue(false); return; }
            if (!ModuleConfig.isEnabled(ModuleId.CLAIM)) return;
            Claim toClaim = ClaimManager.get().getClaimAt(dim, toPos);
            if (toClaim != null && !toClaim.getFlag(ClaimFlag.FLUID_FLOW)) {
                Claim fromClaim = ClaimManager.get().getClaimAt(dim, fromPos);
                if (fromClaim == null || !fromClaim.id.equals(toClaim.id)) {
                    cir.setReturnValue(false);
                }
            }
        }
    }
}
