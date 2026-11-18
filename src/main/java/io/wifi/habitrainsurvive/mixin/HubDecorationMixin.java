package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.hub.HubProtectionListener;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.*;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({BlockAttachedEntity.class, ItemFrame.class, ArmorStand.class, EndCrystal.class})
public abstract class HubDecorationMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void habi$protectDecorations(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        if (!entity.level().isClientSide() && HubProtectionListener.buildDenied(entity.level(), source.getEntity())) cir.setReturnValue(false);
    }
}
