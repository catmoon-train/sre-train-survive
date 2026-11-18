package io.wifi.habitrainsurvive.world;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static io.wifi.habitrainsurvive.world.PendingChunkPreparation.State.*;

class PendingChunkPreparationTest {
    @Test void incompleteLoadReturnsImmediatelyWithoutReadingWorld() {
        var future = new CompletableFuture<Boolean>();
        var job = new PendingChunkPreparation(List.of(future), () -> { fail("World not ready"); return false; }, () -> {});
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            for (int i = 0; i < 1000; i++) assertEquals(PENDING, job.poll());
        });
    }

    @Test void allNineChunksMustCompleteBeforeValidation() {
        var futures = new ArrayList<CompletableFuture<Boolean>>();
        for (int i = 0; i < 9; i++) futures.add(new CompletableFuture<>());
        var checked = new AtomicInteger();
        var job = new PendingChunkPreparation(futures, () -> { checked.incrementAndGet(); return true; }, () -> {});
        for (int i = 0; i < 8; i++) futures.get(i).complete(true);
        assertEquals(PENDING, job.poll());
        assertEquals(0, checked.get());
        futures.get(8).complete(true);
        assertEquals(READY, job.poll());
        assertEquals(1, checked.get());
    }

    @Test void generationCompletionStillWaitsForLoadedWorldChunks() {
        var loaded = new AtomicBoolean();
        var job = new PendingChunkPreparation(List.of(CompletableFuture.completedFuture(true)), loaded::get, () -> {});
        assertEquals(PENDING, job.poll());
        loaded.set(true);
        assertEquals(READY, job.poll());
    }

    @Test void unsuccessfulChunkResultIsNotMistakenForSuccessfulFuture() {
        var job = new PendingChunkPreparation(List.of(CompletableFuture.completedFuture(false)), () -> true, () -> {});
        assertEquals(FAILED, job.poll());
    }

    @Test void exceptionalLoadFailsEvenIfAnotherChunkIsStillPending() {
        var failed = new CompletableFuture<Boolean>();
        failed.completeExceptionally(new IllegalStateException("chunk failed"));
        var job = new PendingChunkPreparation(List.of(new CompletableFuture<>(), failed), () -> true, () -> {});
        assertEquals(FAILED, job.poll());
    }

    @Test void externallyCancelledFutureFailsWithoutThrowing() {
        var future = new CompletableFuture<Boolean>();
        future.cancel(false);
        var job = new PendingChunkPreparation(List.of(future), () -> true, () -> {});
        assertEquals(FAILED, job.poll());
    }

    @Test void nullCompletionCannotBecomeSafeDestination() {
        var job = new PendingChunkPreparation(List.of(CompletableFuture.completedFuture(null)), () -> true, () -> {});
        assertEquals(FAILED, job.poll());
    }

    @Test void cancellationReleasesOnceAndLateCompletionCannotReviveRequest() {
        var releases = new AtomicInteger();
        var future = new CompletableFuture<Boolean>();
        var job = new PendingChunkPreparation(List.of(future), () -> true, releases::incrementAndGet);
        job.close(); job.close();
        assertEquals(1, releases.get());
        assertFalse(future.isCancelled(), "Shared loading futures must not be cancelled");
        future.complete(true);
        assertEquals(FAILED, job.poll());
        assertEquals(1, releases.get());
    }

    @Test void readinessRetainsTicketUntilExplicitCompletionCleanup() {
        var releases = new AtomicInteger();
        var job = new PendingChunkPreparation(List.of(CompletableFuture.completedFuture(true)), () -> true, releases::incrementAndGet);
        assertEquals(READY, job.poll());
        assertEquals(0, releases.get());
        job.close();
        assertEquals(1, releases.get());
    }

    @Test void callerCannotRemovePendingChunksFromPreparation() {
        var list = new ArrayList<CompletableFuture<Boolean>>();
        list.add(new CompletableFuture<>());
        var job = new PendingChunkPreparation(list, () -> true, () -> {});
        list.clear();
        assertEquals(PENDING, job.poll());
    }

    @Test void safeSearchUsesNonBlockingInvokerAndRegistersMixin() throws Exception {
        Path main = Path.of("../../src/main");
        String loader = Files.readString(main.resolve("java/io/wifi/habitrainsurvive/world/TeleportChunkLoader.java"));
        String service = Files.readString(main.resolve("java/io/wifi/habitrainsurvive/world/SafeTeleportService.java"));
        String invoker = Files.readString(main.resolve("java/io/wifi/habitrainsurvive/mixin/ServerChunkCacheAccessor.java"));
        String config = Files.readString(main.resolve("resources/habi_train_survive.mixins.json"));
        assertTrue(invoker.contains("@Invoker(\"getChunkFutureMainThread\")"));
        assertTrue(config.contains("\"ServerChunkCacheAccessor\""));
        assertTrue(loader.contains("habi$requestChunk"));
        assertTrue(loader.contains("getChunkNow"));
        assertTrue(loader.contains("addRegionTicket") && loader.contains("removeRegionTicket"));
        assertFalse(loader.contains(".getChunkFuture("));
        assertFalse(service.contains(".getChunkFuture("));
        assertFalse(loader.contains(".join(") || service.contains(".join("));
        assertTrue(service.contains("MAX_CHUNK_PREPARATIONS = 4"));
        assertTrue(service.contains("now - waiting.startedNanos >= MAX_SEARCH_NANOS"));
        assertTrue(service.indexOf("++waiting.age") < service.indexOf("List<Search> jobs"));
    }
}
