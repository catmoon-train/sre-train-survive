package io.wifi.habitrainsurvive.world;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ResourceResetManagerTest {
    @TempDir Path save;

    private WorldDefinition resource() {
        return new WorldDefinition("habi_train_survive:resource", "Resource", "normal", "resource");
    }
    private WorldConfig config(WorldDefinition... definitions) {
        WorldConfig config = new WorldConfig();
        config.worlds.addAll(List.of(definitions));
        return config;
    }
    private Path original(WorldDefinition definition) throws IOException {
        Path target = WorldPaths.dimension(save, definition.id);
        Files.createDirectories(target.resolve("region"));
        Files.writeString(target.resolve("region/original.mca"), "original chunks");
        return target;
    }
    private WorldState queued(WorldDefinition definition) {
        WorldState state = new WorldState();
        state.world(definition.id).pendingReset = true;
        return state;
    }

    @Test void eligibilityProtectsHubVanillaUnknownAndTraversal() {
        assertTrue(ResourceResetManager.eligible(resource()));
        assertFalse(ResourceResetManager.eligible(null));
        for (String id : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end",
                "other:resource", "habi_train_survive:hub", "habi_train_survive:../resource",
                "habi_train_survive:hub.", "habi_train_survive:con", "habi_train_survive:aux.mca")) {
            WorldDefinition d = resource();
            d.id = id;
            assertFalse(ResourceResetManager.eligible(d), id);
        }
        WorldDefinition d = resource();
        d.kind = "hub";
        assertFalse(ResourceResetManager.eligible(d));
        d.kind = "survival";
        assertFalse(ResourceResetManager.eligible(d));
    }

    @Test void protectedAndUnknownQueueFailsBeforeAnyMove() throws IOException {
        WorldDefinition d = resource();
        Path target = original(d);
        WorldState state = queued(d);
        state.world("minecraft:overworld").pendingReset = true;
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> fail("must not persist")));
        assertEquals("original chunks", Files.readString(target.resolve("region/original.mca")));
        state.worlds.remove("minecraft:overworld");
        state.world("habi_train_survive:unregistered").pendingReset = true;
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> fail("must not persist")));
        assertTrue(Files.exists(target));
    }

    @Test void writesPlanBeforeMovingEntireDirectoryThenCommitsGeneration() throws IOException {
        WorldDefinition d = resource();
        Path target = original(d);
        Files.createDirectories(target.resolve("data"));
        Files.writeString(target.resolve("data/map.dat"), "saved data");
        Path protectedFile = save.resolve("region/permanent.mca");
        Files.createDirectories(protectedFile.getParent());
        Files.writeString(protectedFile, "permanent");
        WorldState state = queued(d);
        WorldState.Runtime r = state.world(d.id);
        r.generation = 8;
        r.spawn = new GlobalPosInfo(d.id, 1, 80, 2, 0, 0);
        r.imported = true;
        long oldReset = r.lastReset;
        AtomicInteger writes = new AtomicInteger();
        String[] backup = new String[1];
        ResourceResetManager.applyPending(save, config(d), state, () -> {
            if (writes.incrementAndGet() == 1) {
                assertTrue(Files.exists(target));
                assertTrue(r.pendingReset);
                assertEquals(9, r.resetGeneration);
                assertEquals(8, r.generation);
                assertTrue(r.resetHadDirectory);
                backup[0] = r.resetBackup;
            } else {
                assertFalse(Files.exists(target));
                assertFalse(r.pendingReset);
            }
        });
        assertEquals(2, writes.get());
        assertEquals(9, r.generation);
        assertTrue(r.lastReset >= oldReset);
        assertNull(r.spawn);
        assertNull(r.resetBackup);
        assertTrue(r.imported, "reset must not cause the configured map to be imported again");
        assertEquals("saved data", Files.readString(save.resolve(backup[0]).resolve("data/map.dat")));
        assertEquals("original chunks", Files.readString(save.resolve(backup[0]).resolve("region/original.mca")));
        assertEquals("permanent", Files.readString(protectedFile));
    }

    @Test void failedPlanWriteNeverMovesOriginal() throws IOException {
        WorldDefinition d = resource();
        Path target = original(d);
        WorldState state = queued(d);
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> { throw new IOException("disk full"); }));
        assertTrue(Files.exists(target.resolve("region/original.mca")));
        assertNull(state.world(d.id).resetBackup);
        assertEquals(0, state.world(d.id).generation);
    }

    @Test void backupDirectoryFailureNeverDeletesOriginal() throws IOException {
        WorldDefinition d = resource();
        Path target = original(d);
        Files.writeString(save.resolve("habi-backups"), "not a directory");
        WorldState state = queued(d);
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> {}));
        assertTrue(Files.exists(target.resolve("region/original.mca")));
        assertTrue(state.world(d.id).pendingReset);
        assertEquals(0, state.world(d.id).generation);
    }

    @Test void recoversMovedDirectoryAfterFailedCommitWithoutMovingAgain() throws IOException {
        WorldDefinition d = resource();
        Path target = original(d);
        WorldState state = queued(d);
        AtomicInteger writes = new AtomicInteger();
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> {
            if (writes.incrementAndGet() == 2) throw new IOException("commit failed");
        }));
        WorldState.Runtime r = state.world(d.id);
        Path backup = save.resolve(r.resetBackup);
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(backup.resolve("region/original.mca")));
        assertTrue(r.pendingReset);
        assertEquals(0, r.generation);
        ResourceResetManager.applyPending(save, config(d), state, () -> {});
        assertEquals(1, r.generation);
        assertFalse(r.pendingReset);
        assertTrue(Files.exists(backup.resolve("region/original.mca")));
    }

    @Test void ambiguousRecoveryCannotMoveNewWorldOverBackup() throws IOException {
        WorldDefinition d = resource();
        Path target = original(d);
        WorldState state = queued(d);
        AtomicInteger writes = new AtomicInteger();
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> {
            if (writes.incrementAndGet() == 2) throw new IOException("commit failed");
        }));
        Path backup = save.resolve(state.world(d.id).resetBackup);
        Files.createDirectories(target);
        Files.writeString(target.resolve("new-world"), "new generation");
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> fail("ambiguous")));
        assertEquals("new generation", Files.readString(target.resolve("new-world")));
        assertEquals("original chunks", Files.readString(backup.resolve("region/original.mca")));
    }

    @Test void missingOriginalLogCannotConsumeNewlyCreatedDirectory() throws IOException {
        WorldDefinition d = resource();
        WorldState state = queued(d);
        AtomicInteger writes = new AtomicInteger();
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> {
            if (writes.incrementAndGet() == 2) throw new IOException("commit failed");
        }));
        Path target = original(d);
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> fail("new world")));
        assertTrue(Files.exists(target.resolve("region/original.mca")));
    }

    @Test void hostileJournalPathsCannotEscapeBackupRoot() throws IOException {
        WorldDefinition d = resource();
        Path target = original(d);
        for (String path : List.of("../outside", "habi-backups/../../region", "/outside", "C:/outside",
                "habi-backups/other-123", "habi-backups/habi_train_survive_resource-1/../../outside")) {
            WorldState state = queued(d);
            WorldState.Runtime r = state.world(d.id);
            r.resetBackup = path;
            r.resetGeneration = 1;
            r.resetHadDirectory = true;
            assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), state, () -> fail("bad journal")), path);
            assertTrue(Files.exists(target.resolve("region/original.mca")));
        }
    }

    @Test void rejectsSymbolicLinksAnywhereInDimension() throws IOException {
        WorldDefinition d = resource();
        Path target = original(d);
        Path outside = save.resolve("outside");
        Files.writeString(outside, "untouched");
        try { Files.createSymbolicLink(target.resolve("link"), outside); }
        catch (IOException | UnsupportedOperationException | SecurityException e) { assumeTrue(false, "symlink unavailable: " + e); }
        assertThrows(IOException.class, () -> ResourceResetManager.applyPending(save, config(d), queued(d), () -> fail("symlink")));
        assertEquals("untouched", Files.readString(outside));
        assertTrue(Files.exists(target.resolve("region/original.mca")));
    }
}
