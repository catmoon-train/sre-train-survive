package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.hub.HubManager;
import io.wifi.habitrainsurvive.world.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class HubRespawnMixin {
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
    private void habi$fallbackRespawn(boolean alive, DimensionTransition.PostDimensionTransition action, CallbackInfoReturnable<DimensionTransition> cir) {
        ServerPlayer p = (ServerPlayer) (Object) this;
        if (!HubManager.get().fallbackRespawn()) return;
        DimensionTransition result = cir.getReturnValue();
        boolean denied = result != null && WorldManager.get().entryProblem(p, result.newLevel().dimension().location().toString(), false) != null;
        if (!denied && (alive || p.getRespawnPosition() != null && result != null && !result.missingRespawnBlock())) return;
        var point = HubManager.get().target();
        if (point == null || point.resolveLevel(p.server) == null || !SafeTeleportService.safe(point.resolveLevel(p.server), point.toBlockPos())) return;
        cir.setReturnValue(new DimensionTransition(point.resolveLevel(p.server), new Vec3(point.x, point.y, point.z), Vec3.ZERO, point.yaw, point.pitch, action));
    }
}
