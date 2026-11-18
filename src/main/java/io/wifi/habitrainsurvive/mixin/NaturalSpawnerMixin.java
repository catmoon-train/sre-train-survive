package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.world.NaturalSpawnManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            at = @At("HEAD"), cancellable = true)
    private static void habi$naturalSpawnBatch(MobCategory category, ServerLevel level, ChunkAccess chunk,
            BlockPos pos, NaturalSpawner.SpawnPredicate predicate, NaturalSpawner.AfterSpawnCallback callback, CallbackInfo ci) {
        if (NaturalSpawnManager.get().skip(level, category, level.random)) ci.cancel();
    }

    @Inject(method = "spawnMobsForChunkGeneration", at = @At("HEAD"), cancellable = true)
    private static void habi$generationAnimals(ServerLevelAccessor level, Holder<Biome> biome,
            ChunkPos pos, RandomSource random, CallbackInfo ci) {
        if (NaturalSpawnManager.get().skip(level, MobCategory.CREATURE, random)) ci.cancel();
    }
}
