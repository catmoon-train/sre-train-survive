package io.wifi.habitrainsurvive.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldPathsTest {
    @TempDir Path temporary;

    @Test void safeIdsResolveInsideDedicatedDimensionAndBackupRootsWithoutCreatingFiles() throws IOException {
        Path save = temporary.resolve("save");
        assertEquals(save.resolve("dimensions/habi_train_survive/maps/world-1.v2"),
                WorldPaths.dimension(save, "habi_train_survive:maps/world-1.v2"));
        assertEquals(save.resolve("habi-backups/habi_train_survive_maps_world-1.v2-123"),
                WorldPaths.backup(save, "habi_train_survive:maps/world-1.v2", 123));
        assertFalse(Files.exists(save));
    }

    @Test void filesystemEntryPointsRevalidateIdsWithoutRelyingOnConfigValidation() {
        Path save = temporary.resolve("save");
        for (String id : new String[] {null, "", "habi_train_survive:", "habi_train_survive:/outside",
                "habi_train_survive:../outside", "habi_train_survive:map/../../outside",
                "habi_train_survive:map/./world", "habi_train_survive:map//world", "habi_train_survive:world/",
                "habi_train_survive:world.", "habi_train_survive:map./world", "habi_train_survive:con",
                "habi_train_survive:aux.mca", "habi_train_survive:maps/prn.data/world",
                "habi_train_survive:nul", "habi_train_survive:com1", "habi_train_survive:com9.mca",
                "habi_train_survive:lpt1.mca", "habi_train_survive:lpt9", "habi_train_survive:C:/outside",
                "habi_train_survive:map\\world", "habi_train_survive:World", "con:world", "aux.mca:world",
                ".:world", "..:world", "namespace.:world"}) {
            assertThrows(IllegalArgumentException.class, () -> WorldPaths.dimension(save, id), String.valueOf(id));
            assertThrows(IllegalArgumentException.class, () -> WorldPaths.backup(save, id, 123), String.valueOf(id));
        }
        assertFalse(Files.exists(save));
    }

    @Test void dimensionOperationsCannotTargetVanillaOrForeignNamespaces() {
        for (String id : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "other:world")) {
            assertThrows(IOException.class, () -> WorldPaths.dimension(temporary, id), id);
        }
    }

    @Test void importDirectoryRequiresRegionAndResolvesNestedRelativeSources() throws IOException {
        Path source = Files.createDirectories(temporary.resolve("imports/maps/example"));
        assertThrows(IOException.class, () -> WorldPaths.importDirectory(temporary, "maps/example"));
        Files.createDirectory(source.resolve("region"));
        assertEquals(source, WorldPaths.importDirectory(temporary, "maps/example"));
    }

    @Test void importPathsCannotEscapeEvenWhenOutsideDirectoryHasRegionData() throws IOException {
        Path server = Files.createDirectories(temporary.resolve("server"));
        Files.createDirectories(server.resolve("outside/region"));
        Files.createDirectories(temporary.resolve("outside/region"));
        for (String path : new String[] {null, "", " ", ".", "../outside", "../../outside",
                "map/../../outside", "/outside", "C:/outside", "map\\outside"}) {
            assertThrows(IOException.class, () -> WorldPaths.importDirectory(server, path), String.valueOf(path));
        }
        assertTrue(Files.isDirectory(server.resolve("outside/region")));
        assertTrue(Files.isDirectory(temporary.resolve("outside/region")));
    }

    @Test void rejectLinksAlsoRejectsLexicallyOutsideTargets() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("save"));
        assertThrows(IOException.class, () -> WorldPaths.rejectLinks(root, temporary.resolve("outside")));
        assertThrows(IOException.class, () -> WorldPaths.rejectLinks(root, temporary.resolve("save-other/world")));
        assertDoesNotThrow(() -> WorldPaths.rejectLinks(root, root.resolve("future/world")));
    }

    @Test void dimensionPathsRejectLinkedNamespaceAncestors() throws IOException {
        Path save = Files.createDirectories(temporary.resolve("save/dimensions")).getParent();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        link(save.resolve("dimensions/habi_train_survive"), outside);
        assertThrows(IOException.class, () -> WorldPaths.dimension(save, "habi_train_survive:world"));
        assertFalse(Files.exists(outside.resolve("world")));
    }

    @Test void dimensionPathsRejectDanglingTargetLinks() throws IOException {
        Path namespace = Files.createDirectories(temporary.resolve("dimensions/habi_train_survive"));
        link(namespace.resolve("world"), temporary.resolve("missing"));
        assertThrows(IOException.class, () -> WorldPaths.dimension(temporary, "habi_train_survive:world"));
    }

    @Test void rootsCannotBeReachedThroughSymlinkAliases() throws IOException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path alias = temporary.resolve("alias");
        link(alias, outside);
        assertThrows(IOException.class, () -> WorldPaths.dimension(alias.resolve("save"), "habi_train_survive:world"));
        assertThrows(IOException.class, () -> WorldPaths.backup(alias.resolve("save"), "habi_train_survive:world", 123));
        Files.createDirectories(outside.resolve("imports/map/region"));
        assertThrows(IOException.class, () -> WorldPaths.importDirectory(alias, "map"));
    }

    @Test void backupPathsRejectLinkedBackupRootAndExistingTarget() throws IOException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path save = Files.createDirectory(temporary.resolve("save"));
        link(save.resolve("habi-backups"), outside);
        assertThrows(IOException.class, () -> WorldPaths.backup(save, "habi_train_survive:world", 123));
        Path otherSave = Files.createDirectories(temporary.resolve("other-save/habi-backups")).getParent();
        link(otherSave.resolve("habi-backups/habi_train_survive_world-123"), outside);
        assertThrows(IOException.class, () -> WorldPaths.backup(otherSave, "habi_train_survive:world", 123));
    }

    @Test void importsRejectLinkedSourcesAndLinksInsideOtherwiseValidSourceTrees() throws IOException {
        Path imports = Files.createDirectory(temporary.resolve("imports"));
        Path outside = Files.createDirectories(temporary.resolve("outside/region")).getParent();
        link(imports.resolve("linked-map"), outside);
        assertThrows(IOException.class, () -> WorldPaths.importDirectory(temporary, "linked-map"));
        Path source = Files.createDirectories(imports.resolve("map/region")).getParent();
        link(source.resolve("playerdata"), outside);
        assertThrows(IOException.class, () -> WorldPaths.importDirectory(temporary, "map"));
    }

    private static void link(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException e) { assumeTrue(false, "symlink unavailable: " + e); }
    }
}
