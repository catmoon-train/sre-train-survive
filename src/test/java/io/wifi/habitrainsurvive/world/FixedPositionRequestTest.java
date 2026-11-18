package io.wifi.habitrainsurvive.world;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FixedPositionRequestTest {
    private GlobalPosInfo point() {
        var pos = new GlobalPosInfo();
        pos.dimension = "minecraft:overworld";
        pos.x = -32.25; pos.y = 72; pos.z = 64.5;
        pos.yaw = 90; pos.pitch = -30; pos.generation = 17;
        return pos;
    }

    @Test void snapshotPreservesCoordinatesOrientationAndOriginalGeneration() {
        var original = point(); var copy = original.copy();
        assertNotSame(original, copy);
        assertEquals(original.dimension, copy.dimension);
        assertEquals(original.x, copy.x); assertEquals(original.y, copy.y); assertEquals(original.z, copy.z);
        assertEquals(original.yaw, copy.yaw); assertEquals(original.pitch, copy.pitch);
        assertEquals(17, copy.generation);
    }

    @Test void configMutationCannotMoveAlreadyQueuedDestination() {
        var original = point(); var copy = original.copy();
        original.dimension = "minecraft:the_end"; original.x = 99; original.y = 99; original.z = 99;
        original.yaw = 1; original.pitch = 1; original.generation = 18;
        assertEquals("minecraft:overworld", copy.dimension);
        assertEquals(-32.25, copy.x); assertEquals(72, copy.y); assertEquals(64.5, copy.z);
        assertEquals(90, copy.yaw); assertEquals(-30, copy.pitch); assertEquals(17, copy.generation);
    }

    @Test void validCoordinatesIncludeNegativeAndFractionalPositions() {
        assertTrue(SafeTeleportService.validPosition(point()));
        var pos = point(); pos.x = -29999984; pos.z = 29999984;
        assertTrue(SafeTeleportService.validPosition(pos));
        pos.x = -29999985;
        assertFalse(SafeTeleportService.validPosition(pos));
    }

    @Test void invalidDimensionAndNullTargetsAreRejected() {
        assertFalse(SafeTeleportService.validPosition(null));
        var pos = point(); pos.dimension = null;
        assertFalse(SafeTeleportService.validPosition(pos));
        pos.dimension = "";
        assertFalse(SafeTeleportService.validPosition(pos));
    }

    @Test void nonFiniteCoordinatesAndOrientationAreRejected() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var x = point(); x.x = invalid; assertFalse(SafeTeleportService.validPosition(x));
            var y = point(); y.y = invalid; assertFalse(SafeTeleportService.validPosition(y));
            var z = point(); z.z = invalid; assertFalse(SafeTeleportService.validPosition(z));
        }
        var yaw = point(); yaw.yaw = Float.NaN; assertFalse(SafeTeleportService.validPosition(yaw));
        var pitch = point(); pitch.pitch = Float.POSITIVE_INFINITY; assertFalse(SafeTeleportService.validPosition(pitch));
    }

    private String source(String name) throws Exception {
        return Files.readString(Path.of("../../src/main/java/io/wifi/habitrainsurvive").resolve(name));
    }

    @Test void safetyChecksRejectUnloadedChunksBeforeBlockOrHeightReads() throws Exception {
        String service = source("world/SafeTeleportService.java");
        String safe = service.substring(service.indexOf("public static boolean safe("));
        assertTrue(safe.indexOf("getChunkNow") < safe.indexOf("level.getBlockState"));
        String column = service.substring(service.indexOf("public static BlockPos safeColumn("));
        assertTrue(column.indexOf("getChunkNow") < column.indexOf("level.getHeight"));
        String position = service.substring(service.indexOf("public static boolean position("));
        assertTrue(position.indexOf("TeleportChunkLoader.ready") < position.indexOf("!safe(level, feet)"));
    }

    @Test void queueAdmissionNeverPerformsTeleportOrFeeSettlement() throws Exception {
        String service = source("world/SafeTeleportService.java");
        String queue = service.substring(service.indexOf("public boolean queuePosition("), service.indexOf("static boolean validPosition("));
        assertTrue(queue.contains("pos.copy()"));
        assertFalse(queue.contains("paidPosition(") || queue.contains("withdraw(") || queue.contains("onSuccess.accept("));
        assertTrue(service.contains("if (moved) search.onSuccess.accept(player)"));
        assertTrue(service.contains("if (search.internal) manager.recover(commit); else commit.run()"));
        assertTrue(service.contains("search.valid.getAsBoolean()"));
    }

    @Test void warmupsWarpsTpaAndRecoveryUseAsyncApi() throws Exception {
        String warmup = source("security/TeleportWarmupManager.java");
        assertTrue(warmup.contains("targetPos.teleportAsync(player, moved ->"));
        assertFalse(warmup.contains("targetPos.teleport(player)"));
        String commands = source("cmi/CmiCommands.java");
        assertTrue(commands.contains("LandmarkManager.teleport(p, name)"));
        String landmarks = source("landmark/LandmarkManager.java");
        assertTrue(landmarks.contains("queuePosition("));
        assertFalse(landmarks.contains("SafeTeleportService.paidPosition("));
        assertFalse(commands.contains("SafeTeleportService.paidPosition("));
        assertTrue(commands.contains("() -> !sender.hasDisconnected()"));
        assertTrue(commands.contains("() -> !target.hasDisconnected()"));
        assertFalse(source("cmi/CmiManager.java").contains(".teleport(player)"));
        assertFalse(source("claim/ClaimManager.java").contains(".teleport(player)"));
        assertFalse(source("hub/HubManager.java").contains(".teleport(player)"));
    }
}
