package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.chestshop.ChestShopManager;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public class HopperBlockEntityMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void habi$preventHopperStealingFromChestShop(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (level.isClientSide() || !ModuleConfig.isEnabled(ModuleId.CHEST_SHOP)) return;
        BlockPos above = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0D, hopper.getLevelZ());
        String dim = level.dimension().location().toString();
        if (ChestShopManager.get().getShopAt(dim, above) != null) {
            cir.setReturnValue(false);
        }
    }
}
