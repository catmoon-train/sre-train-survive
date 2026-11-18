package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.hub.HubProtectionListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep regeneration intact while disabling hunger/exhaustion only in configured worlds. */
@Mixin(FoodData.class)
public abstract class WorldHungerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void habi$worldFoodPolicy(Player player, CallbackInfo ci) {
        if (player.level().isClientSide()) return;
        var policy = HubProtectionListener.policy(player.level());
        if (policy != null && !policy.hunger) {
            FoodData food = (FoodData) (Object) this;
            food.setFoodLevel(20);
            food.setSaturation(5);
            food.setExhaustion(0);
        }
    }
}
