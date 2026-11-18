package io.wifi.habitrainsurvive.world;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** 主线程跨 tick 轮询，不等待或取消可能由其他加载请求共享的 future。 */
final class PendingChunkPreparation implements AutoCloseable {
    enum State { PENDING, READY, FAILED }

    private final List<CompletableFuture<Boolean>> chunks;
    private final BooleanSupplier loaded;
    private final Runnable release;
    private boolean closed;

    PendingChunkPreparation(List<CompletableFuture<Boolean>> chunks, BooleanSupplier loaded, Runnable release) {
        this.chunks = List.copyOf(chunks);
        this.loaded = loaded;
        this.release = release;
    }

    State poll() {
        if (closed) return State.FAILED;
        boolean pending = false;
        for (var chunk : chunks) {
            if (!chunk.isDone()) { pending = true; continue; }
            if (chunk.isCompletedExceptionally() || !Boolean.TRUE.equals(chunk.getNow(false))) return State.FAILED;
        }
        return !pending && loaded.getAsBoolean() ? State.READY : State.PENDING;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        release.run();
    }
}
