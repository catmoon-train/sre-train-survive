package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.claim.Claim;
import io.wifi.habitrainsurvive.claim.ClaimFlag;
import io.wifi.habitrainsurvive.claim.ClaimManager;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.town.Town;
import io.wifi.habitrainsurvive.town.TownManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmBlock.class)
public class FarmBlockMixin {
    @Inject(method = "fallOn", at = @At("HEAD"), cancellable = true)
    private void habi$preventFarmlandTrample(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance, CallbackInfo ci) {
        if (level.isClientSide()) return;
        String dim = level.dimension().location().toString();
        if (io.wifi.habitrainsurvive.hub.HubProtectionListener.buildDenied(level, entity)) { ci.cancel(); return; }
        if (ModuleConfig.isEnabled(ModuleId.CLAIM)) {
            Claim claim = ClaimManager.get().getClaimAt(dim, pos);
            if (claim != null) {
                if (entity instanceof ServerPlayer sp && claim.canBreak(sp.getUUID())) {
                    return;
                }
                if (!claim.getFlag(ClaimFlag.CROP_TRAMPLE)) {
                    entity.causeFallDamage(fallDistance, 1.0F, entity.damageSources().fall());
                    ci.cancel();
                    return;
                }
            }
        }
        if (ModuleConfig.isEnabled(ModuleId.TOWN)) {
            Town town = TownManager.get().getTownAt(dim, pos);
            if (town != null) {
                if (entity instanceof ServerPlayer sp && town.canBuild(dim, sp.getUUID(), pos)) {
                    return;
                }
                entity.causeFallDamage(fallDistance, 1.0F, entity.damageSources().fall());
                ci.cancel();
            }
        }
    }
}
