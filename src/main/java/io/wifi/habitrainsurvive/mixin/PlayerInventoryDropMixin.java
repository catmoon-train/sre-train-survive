package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.grave.GraveManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Inventory.class)
public class PlayerInventoryDropMixin {
    @Shadow
    @Final
    public Player player;

    @Inject(method = "dropAll", at = @At("HEAD"), cancellable = true)
    private void habi$saveToGraveBeforeDropAll(CallbackInfo ci) {
        if (this.player instanceof ServerPlayer sp && !sp.level().isClientSide() && ModuleConfig.isEnabled(ModuleId.GRAVE)) {
            Inventory self = (Inventory) (Object) this;
            if (GraveManager.get().captureInventoryOnDeath(sp, self)) {
                ci.cancel();
            }
        }
    }
}
