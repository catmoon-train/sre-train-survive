package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.world.NaturalSpawnManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({PhantomSpawner.class, PatrolSpawner.class, VillageSiege.class})
public abstract class NaturalMonsterSpawnerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void habi$blockNaturalMonsters(ServerLevel level, boolean monsters, boolean animals, CallbackInfoReturnable<Integer> cir) {
        if (NaturalSpawnManager.get().skip(level, MobCategory.MONSTER, level.random)) cir.setReturnValue(0);
    }
}
