package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.world.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NetherPortalBlock.class)
public abstract class NetherWorldRouteMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void habi$portalPolicy(ServerLevel source, Entity entity, BlockPos entrance, CallbackInfoReturnable<DimensionTransition> cir) {
        WorldDefinition d = WorldManager.get().definition(source.dimension().location().toString());
        if (d != null && !d.portals) cir.setReturnValue(null);
    }
    @Redirect(method = "getPortalDestination", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel habi$pairedWorld(MinecraftServer server, ResourceKey<Level> vanilla, ServerLevel source, Entity entity, BlockPos entrance) {
        WorldDefinition d = WorldManager.get().definition(source.dimension().location().toString());
        ResourceKey<Level> key = d == null ? vanilla : ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(d.preset.equals("nether") ? d.overworld : d.nether));
        ServerLevel target = server.getLevel(key);
        if (target != null && entity instanceof ServerPlayer p && !WorldManager.get().guard(p, target)) return null;
        if (target != null && !(entity instanceof ServerPlayer)) {
            WorldDefinition dest = WorldManager.get().definition(target.dimension().location().toString());
            if (dest != null && (!dest.open || WorldManager.get().runtime(dest.id).pendingReset || !dest.permission.isEmpty() || dest.entryFee > 0)) return null;
        }
        return target;
    }
}
