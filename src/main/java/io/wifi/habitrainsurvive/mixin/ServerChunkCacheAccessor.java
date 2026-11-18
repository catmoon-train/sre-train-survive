package io.wifi.habitrainsurvive.mixin;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/** 仅提交主线程区块任务；公开 getChunkFuture 会 managedBlock，不能用于异步准备。 */
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheAccessor {
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> habi$requestChunk(int x, int z, ChunkStatus status, boolean create);
}
