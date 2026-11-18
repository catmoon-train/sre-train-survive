package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.claim.Claim;
import io.wifi.habitrainsurvive.claim.ClaimManager;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public class ItemEntityMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void habi$preventUnauthorizedItemPickup(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp) || sp.hasPermissions(2) || !ModuleConfig.isEnabled(ModuleId.CLAIM)) return;
        ItemEntity self = (ItemEntity) (Object) this;
        String dim = sp.serverLevel().dimension().location().toString();
        Claim claim = ClaimManager.get().getClaimAt(dim, self.blockPosition());
        if (claim != null && !claim.canPickupItem(sp.getUUID())) {
            ci.cancel();
        }
    }
}
