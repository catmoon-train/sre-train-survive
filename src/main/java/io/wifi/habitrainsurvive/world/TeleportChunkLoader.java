package io.wifi.habitrainsurvive.world;

import io.wifi.habitrainsurvive.mixin.ServerChunkCacheAccessor;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 用独立票据准备落点周围 3×3 区块，避免边界碰撞检查重新触发同步加载。 */
final class TeleportChunkLoader {
    private static final TicketType<UUID> TICKET = TicketType.create("habi_safe_teleport", UUID::compareTo);

    static PendingChunkPreparation prepare(ServerLevel level, int x, int z) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Teleport preparation must run on server thread");
        ServerChunkCache cache = level.getChunkSource();
        ChunkPos center = new ChunkPos(x, z);
        UUID token = UUID.randomUUID();
        // radius=1 对应中心 level 32、邻居 level 33，均至少保持 FULL。
        cache.addRegionTicket(TICKET, center, 1, token);
        Runnable release = () -> cache.removeRegionTicket(TICKET, center, 1, token);
        try {
            var chunks = new ArrayList<CompletableFuture<Boolean>>(9);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                chunks.add(((ServerChunkCacheAccessor) cache).habi$requestChunk(x + dx, z + dz, ChunkStatus.FULL, true)
                        .thenApply(result -> result != null && result.isSuccess()));
            }
            return new PendingChunkPreparation(chunks, () -> loaded(cache, x, z), release);
        } catch (RuntimeException | Error error) {
            release.run();
            throw error;
        }
    }

    static boolean ready(ServerLevel level, int x, int z) { return loaded(level.getChunkSource(), x, z); }

    private static boolean loaded(ServerChunkCache cache, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (cache.getChunkNow(x + dx, z + dz) == null) return false;
        }
        return true;
    }

    private TeleportChunkLoader() { }
}
