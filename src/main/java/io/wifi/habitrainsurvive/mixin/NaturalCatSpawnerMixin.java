package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.world.NaturalSpawnManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.npc.CatSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CatSpawner.class)
public abstract class NaturalCatSpawnerMixin {
    @Inject(method = "spawnCat", at = @At("HEAD"), cancellable = true)
    private void habi$reduceNaturalCats(BlockPos pos, ServerLevel level, CallbackInfoReturnable<Integer> cir) {
        if (NaturalSpawnManager.get().skip(level, MobCategory.CREATURE, level.random)) cir.setReturnValue(0);
    }
}
