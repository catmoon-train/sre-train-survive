package io.wifi.habitrainsurvive.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldImporterTest {
    @TempDir Path temporary;
    Path save, server, source;
    WorldDefinition definition;
    WorldConfig config;
    WorldState state;

    @BeforeEach void setup() throws IOException {
        save = Files.createDirectories(temporary.resolve("save"));
        server = Files.createDirectories(temporary.resolve("server"));
        source = Files.createDirectories(server.resolve("imports/map/region")).getParent();
        definition = new WorldDefinition("habi_train_survive:custom", "Imported", "normal", "survival");
        definition.seed = 123L;
        definition.importSource = "map";
        config = new WorldConfig();
        config.worlds.add(definition);
        state = new WorldState();
        Files.write(source.resolve("region/r.0.0.mca"), region());
        manifest("normal", 3955, 123);
    }
    private void apply(ResourceResetManager.IOAction persist) throws IOException {
        WorldImporter.applyImports(save, server, config, state, persist);
    }
    private Path target() throws IOException { return WorldPaths.dimension(save, definition.id); }
    private Path journal() throws IOException {
        try (var files = Files.list(save.resolve("habi-import-journals"))) {
            return files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
        }
    }
    private JsonObject plan() throws IOException { return JsonParser.parseString(Files.readString(journal())).getAsJsonObject(); }
    private void manifest(String preset, int version, long seed) throws IOException {
        Files.writeString(source.resolve("manifest.json"), "{\"format\":1,\"preset\":\"" + preset + "\",\"version\":" + version + ",\"seed\":" + seed + "}");
    }
    private void failCommit() throws IOException {
        assertThrows(IOException.class, () -> apply(() -> { throw new IOException("state write failed"); }));
        assertFalse(state.world(definition.id).imported);
        assertTrue(Files.isDirectory(target()));
    }

    @Test void importsOnlyAnvilAndFilteredDataAndIsOneShot() throws IOException {
        Files.createDirectories(source.resolve("entities"));
        Files.write(source.resolve("entities/r.0.0.mca"), region());
        Files.createDirectories(source.resolve("poi"));
        Files.write(source.resolve("poi/r.0.0.mca"), region());
        Files.createDirectories(source.resolve("data/nested"));
        Files.writeString(source.resolve("data/nested/map.dat"), "map data");
        Files.writeString(source.resolve("data/session.lock"), "do not copy");
        Files.createDirectories(source.resolve("data/playerdata"));
        Files.writeString(source.resolve("data/playerdata/player.dat"), "identity");
        for (String name : List.of("playerdata", "advancements", "stats")) {
            Files.createDirectories(source.resolve(name));
            Files.writeString(source.resolve(name).resolve("player.json"), "identity");
        }
        Files.writeString(source.resolve("session.lock"), "foreign lock");
        levelDat(3955, 123, "minecraft:overworld");
        apply(() -> assertTrue(state.world(definition.id).imported));
        Path target = target();
        assertArrayEquals(region(), Files.readAllBytes(target.resolve("region/r.0.0.mca")));
        assertTrue(Files.exists(target.resolve("entities/r.0.0.mca")));
        assertTrue(Files.exists(target.resolve("poi/r.0.0.mca")));
        assertEquals("map data", Files.readString(target.resolve("data/nested/map.dat")));
        for (String name : List.of("playerdata", "advancements", "stats", "session.lock", "level.dat", "manifest.json", "data/session.lock", "data/playerdata"))
            assertFalse(Files.exists(target.resolve(name)), name);
        assertTrue(Files.exists(source.resolve("level.dat")), "source must remain untouched");
        assertTrue(Files.exists(target.resolve(".habi-anvil-import.json")));
        Files.writeString(target.resolve("new-game-data"), "keep");
        apply(() -> fail("already imported"));
        assertEquals("keep", Files.readString(target.resolve("new-game-data")));
    }

    @Test void neverOverwritesEvenAnEmptyExistingTarget() throws IOException {
        Files.createDirectories(target());
        assertThrows(IOException.class, () -> apply(() -> fail("conflict")));
        assertFalse(state.world(definition.id).imported);
        try (var files = Files.list(target())) { assertEquals(0, files.count()); }
    }

    @Test void refusesBuiltHubAndUnmanagedWorldAndSkipsDisabled() throws IOException {
        definition.id = "habi_train_survive:hub";
        definition.kind = "hub";
        state.world(definition.id).hubBuilt = true;
        assertThrows(IOException.class, () -> apply(() -> fail("built hub")));
        assertFalse(Files.exists(target()));
        definition.id = "minecraft:overworld";
        assertThrows(IOException.class, () -> apply(() -> fail("vanilla")));
        definition.enabled = false;
        apply(() -> fail("disabled"));
    }

    @Test void anUntrackedMarkerCannotAuthorizeExistingMap() throws IOException {
        Files.createDirectories(target());
        Files.writeString(target().resolve(".habi-anvil-import.json"), "{\"imported\":true}");
        assertThrows(IOException.class, () -> apply(() -> fail("no journal")));
        assertFalse(state.world(definition.id).imported);
    }

    @Test void recoversRenameBeforeStateCommitEvenWithoutSource() throws IOException {
        failCommit();
        Files.move(source, temporary.resolve("source-removed"));
        // Simulate a freshly deserialized pre-commit state rather than relying on in-memory rollback.
        state = new WorldState();
        apply(() -> {});
        assertTrue(state.world(definition.id).imported);
        assertArrayEquals(region(), Files.readAllBytes(target().resolve("region/r.0.0.mca")));
    }

    @Test void recoversCompleteStagingBeforeRename() throws IOException {
        failCommit();
        Path staging = save.resolve("habi-import-staging").resolve(plan().get("token").getAsString());
        Files.move(target(), staging, StandardCopyOption.ATOMIC_MOVE);
        apply(() -> {});
        assertTrue(state.world(definition.id).imported);
        assertTrue(Files.exists(target().resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(staging));
    }

    @Test void recoveryRejectsAlteredMarkerAndPayload() throws IOException {
        failCommit();
        Path marker = target().resolve(".habi-anvil-import.json");
        String original = Files.readString(marker);
        Files.writeString(marker, "{}");
        assertThrows(IOException.class, () -> apply(() -> fail("bad marker")));
        Files.writeString(marker, original);
        Files.writeString(target().resolve("unrelated-map-data"), "not in journal");
        assertThrows(IOException.class, () -> apply(() -> fail("digest mismatch")));
        assertFalse(state.world(definition.id).imported);
        assertEquals("not in journal", Files.readString(target().resolve("unrelated-map-data")));
    }

    @Test void recoveryRejectsBothDirectoriesAndMaliciousJournalToken() throws IOException {
        failCommit();
        JsonObject plan = plan();
        Path staging = save.resolve("habi-import-staging").resolve(plan.get("token").getAsString());
        Files.createDirectory(staging);
        assertThrows(IOException.class, () -> apply(() -> fail("both directories")));
        plan.addProperty("token", "../../outside");
        Files.writeString(journal(), plan.toString());
        assertThrows(IOException.class, () -> apply(() -> fail("traversal token")));
        assertTrue(Files.exists(target().resolve("region/r.0.0.mca")));
    }

    @Test void requiresExplicitSeedEvenWithKnownManifestSeed() {
        definition.seed = null;
        assertThrows(IOException.class, () -> apply(() -> fail("unknown seed")));
    }

    @Test void rejectsSourceTraversalAndManifestMismatches() throws IOException {
        definition.importSource = "map/../map";
        assertThrows(IOException.class, () -> apply(() -> fail("traversal")));
        definition.importSource = "map";
        manifest("nether", 3955, 123);
        assertThrows(IOException.class, () -> apply(() -> fail("wrong preset")));
        manifest("normal", 3955, 456);
        assertThrows(IOException.class, () -> apply(() -> fail("wrong seed")));
        manifest("normal", 3956, 123);
        assertThrows(IOException.class, () -> apply(() -> fail("newer version")));
        manifest("normal", 100, 123);
        assertThrows(IOException.class, () -> apply(() -> fail("old version")));
        assertFalse(Files.exists(target()));
    }

    @Test void rejectsMissingMetadataAndEmptyOrNonstandardAnvil() throws IOException {
        Files.delete(source.resolve("manifest.json"));
        assertThrows(IOException.class, () -> apply(() -> fail("no metadata")));
        manifest("normal", 3955, 123);
        Files.write(source.resolve("region/r.0.0.mca"), new byte[8192]);
        assertThrows(IOException.class, () -> apply(() -> fail("no chunks")));
        Files.write(source.resolve("region/r.0.0.mca"), region());
        Files.writeString(source.resolve("region/not-anvil.txt"), "unknown");
        assertThrows(IOException.class, () -> apply(() -> fail("not anvil")));
        assertFalse(Files.exists(target()));
    }

    @Test void readsLevelNbtWithoutMinecraftBootstrap() throws IOException {
        Files.delete(source.resolve("manifest.json"));
        levelDat(3955, 123, "minecraft:overworld");
        apply(() -> {});
        assertTrue(state.world(definition.id).imported);
        assertFalse(Files.exists(target().resolve("level.dat")));
    }

    @Test void manifestCannotOverrideBadNbtVersionSeedOrType() throws IOException {
        levelDat(3956, 123, "minecraft:overworld");
        assertThrows(IOException.class, () -> apply(() -> fail("future NBT")));
        levelDat(3462, 123, "minecraft:overworld");
        assertThrows(IOException.class, () -> apply(() -> fail("old NBT")));
        levelDat(3955, 456, "minecraft:overworld");
        assertThrows(IOException.class, () -> apply(() -> fail("NBT seed mismatch")));
        levelDat(3955, 123, "minecraft:nether");
        assertThrows(IOException.class, () -> apply(() -> fail("NBT type mismatch")));
        assertFalse(Files.exists(target()));
    }

    @Test void copiesExternalChunksAndRejectsMissingExternalPayload() throws IOException {
        byte[] region = region();
        ByteBuffer.wrap(region).putInt(8192, 1).put(8196, (byte) 130);
        Files.write(source.resolve("region/r.0.0.mca"), region);
        assertThrows(IOException.class, () -> apply(() -> fail("missing mcc")));
        Files.write(source.resolve("region/c.0.0.mcc"), new byte[] {1, 2, 3});
        apply(() -> {});
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target().resolve("region/c.0.0.mcc")));
    }

    @Test void rejectsSourceLinksEvenInExcludedPlayerData() throws IOException {
        Path outside = temporary.resolve("outside");
        Files.writeString(outside, "identity");
        link(source.resolve("playerdata"), outside);
        assertThrows(IOException.class, () -> apply(() -> fail("source symlink")));
        assertFalse(Files.exists(target()));
        assertEquals("identity", Files.readString(outside));
    }

    @Test void rejectsStagingRootSymlink() throws IOException {
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        link(save.resolve("habi-import-staging"), outside);
        assertThrows(IOException.class, () -> apply(() -> fail("staging symlink")));
        assertFalse(Files.exists(target()));
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }

    private void link(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException e) { assumeTrue(false, "symlink unavailable: " + e); }
    }

    private static byte[] region() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream chunk = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            tag(chunk, 10, "");
            tag(chunk, 3, "DataVersion"); chunk.writeInt(3955);
            chunk.writeByte(0);
        }
        byte[] compressed = bytes.toByteArray();
        ByteBuffer region = ByteBuffer.allocate(12288);
        region.putInt(0, (2 << 8) | 1);
        region.position(8192);
        region.putInt(compressed.length + 1).put((byte) 2).put(compressed);
        return region.array();
    }
    private static void tag(DataOutputStream out, int type, String name) throws IOException {
        out.writeByte(type);
        out.writeUTF(name);
    }
    private void levelDat(int version, long seed, String noise) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(source.resolve("level.dat"))))) {
            tag(out, 10, "");
            tag(out, 10, "Data");
            tag(out, 3, "DataVersion"); out.writeInt(version);
            tag(out, 10, "WorldGenSettings");
            tag(out, 4, "seed"); out.writeLong(seed);
            tag(out, 10, "dimensions");
            tag(out, 10, "minecraft:overworld");
            tag(out, 8, "type"); out.writeUTF("minecraft:overworld");
            tag(out, 10, "generator");
            tag(out, 8, "type"); out.writeUTF("minecraft:noise");
            tag(out, 8, "settings"); out.writeUTF(noise);
            for (int i = 0; i < 6; i++) out.writeByte(0);
        }
    }
}
