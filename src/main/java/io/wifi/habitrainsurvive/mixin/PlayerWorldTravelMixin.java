package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.world.WorldManager;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.Set;

@Mixin(ServerPlayer.class)
public abstract class PlayerWorldTravelMixin {
    @Unique private GlobalPosInfo habi$origin;
    @Unique private GlobalPosInfo habi$portalOrigin;
    @Unique private boolean habi$wrapped;
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", at = @At("HEAD"), cancellable = true)
    private void habi$checkTeleport(ServerLevel level, double x, double y, double z, Set<RelativeMovement> movement, float yaw, float pitch, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer p = (ServerPlayer) (Object) this;
        if (!WorldManager.get().guard(p, level)) { cir.setReturnValue(false); return; }
        habi$origin = GlobalPosInfo.fromPlayer(p); habi$wrapped = true;
    }
    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", at = @At("RETURN"))
    private void habi$completeTeleport(ServerLevel level, double x, double y, double z, Set<RelativeMovement> movement, float yaw, float pitch, CallbackInfoReturnable<Boolean> cir) {
        if (habi$wrapped && cir.getReturnValueZ()) WorldManager.get().finishTravel((ServerPlayer) (Object) this, habi$origin);
        habi$origin = null; habi$wrapped = false;
    }
    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void habi$checkPortal(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        ServerPlayer p = (ServerPlayer) (Object) this;
        if (!WorldManager.get().guard(p, transition.newLevel())) { cir.setReturnValue(null); return; }
        habi$portalOrigin = habi$wrapped ? null : GlobalPosInfo.fromPlayer(p);
    }
    @Inject(method = "changeDimension", at = @At("RETURN"))
    private void habi$completePortal(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        if (!habi$wrapped && cir.getReturnValue() != null) WorldManager.get().finishTravel((ServerPlayer) (Object) this, habi$portalOrigin);
        habi$portalOrigin = null;
    }
    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"), cancellable = true)
    private void habi$checkLocal(double x, double y, double z, CallbackInfo ci) {
        ServerPlayer p = (ServerPlayer) (Object) this;
        p.teleportTo(p.serverLevel(), x, y, z, Set.of(), p.getYRot(), p.getXRot()); ci.cancel();
    }
}
