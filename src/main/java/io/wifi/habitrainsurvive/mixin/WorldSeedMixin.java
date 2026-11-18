package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.world.WorldManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class WorldSeedMixin {
    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void habi$worldSeed(CallbackInfoReturnable<Long> cir) {
        ServerLevel level = (ServerLevel) (Object) this;
        String id = level.dimension().location().toString();
        if (id.startsWith("habi_train_survive:"))
            cir.setReturnValue(WorldManager.get().seed(id, level.getServer().getWorldData().worldGenOptions().seed()));
    }
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/WorldOptions;seed()J"))
    private long habi$structureSeed(net.minecraft.world.level.levelgen.WorldOptions options) {
        ServerLevel level = (ServerLevel) (Object) this;
        return WorldManager.get().seed(level.dimension().location().toString(), options.seed());
    }
}
