package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.claim.Claim;
import io.wifi.habitrainsurvive.claim.ClaimFlag;
import io.wifi.habitrainsurvive.claim.ClaimManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public class FireBlockMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void habi$preventFireSpreadInClaim(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        String dim = level.dimension().location().toString();
        var worldPolicy = io.wifi.habitrainsurvive.hub.HubProtectionListener.policy(level);
        if (worldPolicy != null && !worldPolicy.fireSpread) { ci.cancel(); return; }
        Claim claim = ClaimManager.get().getClaimAt(dim, pos);
        if (claim != null && !claim.getFlag(ClaimFlag.FIRE_SPREAD)) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            ci.cancel();
        }
    }
}
