package io.wifi.habitrainsurvive.mixin;

import io.wifi.habitrainsurvive.world.*;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Mixin(MinecraftServer.class)
public abstract class WorldCreationMixin {
    @Inject(method = "createLevels", at = @At("HEAD"))
    private void habi$beforeOpenWorlds(ChunkProgressListener listener, CallbackInfo ci) {
        WorldManager.get().prepare((MinecraftServer) (Object) this);
    }
    @Redirect(method = "createLevels", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Registry;entrySet()Ljava/util/Set;"))
    private Set<Map.Entry<ResourceKey<LevelStem>, LevelStem>> habi$enabledWorlds(Registry<LevelStem> registry) {
        return registry.entrySet().stream().filter(e -> {
            String id = e.getKey().location().toString();
            boolean enabled = WorldManager.get().startupEnabled(id);
            if (!enabled) io.wifi.habitrainsurvive.HabiTrainSurvive.LOGGER.info("不加载已停用维度: {}", id);
            return enabled;
        })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
    @Redirect(method = "createLevels", at = @At(value = "NEW", target = "net/minecraft/server/level/ServerLevel"))
    private ServerLevel habi$independentWorldData(MinecraftServer server, Executor executor,
            LevelStorageSource.LevelStorageAccess storage, ServerLevelData data, ResourceKey<Level> key,
            LevelStem stem, ChunkProgressListener listener, boolean debug, long biomeSeed,
            List<CustomSpawner> spawners, boolean tickTime, RandomSequences randomSequences) {
        String id = key.location().toString(); WorldDefinition d = WorldManager.get().definition(id);
        if (d != null && d.managed()) {
            data = new ManagedLevelData(server.getWorldData(), data, id);
            biomeSeed = BiomeManager.obfuscateSeed(WorldManager.get().seed(id, server.getWorldData().worldGenOptions().seed()));
            randomSequences = null;
        }
        return new ServerLevel(server, executor, storage, data, key, stem, listener, debug, biomeSeed, spawners, tickTime, randomSequences);
    }
}
