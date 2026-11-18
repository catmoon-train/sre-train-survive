package io.wifi.habitrainsurvive.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NaturalSpawnConfigTest {
    @TempDir Path directory;

    @Test void defaultBlocksOverworldMonstersRegardlessOfAnimalRandomResult() {
        NaturalSpawnConfig config = new NaturalSpawnConfig();
        assertTrue(config.enabled);
        assertTrue(config.skip(true, true, false, true));
        assertTrue(config.skip(true, true, false, false));
    }

    @Test void animalBatchesAcceptExactlyOneOfTheTwoEquallyLikelyRandomResults() {
        NaturalSpawnConfig config = new NaturalSpawnConfig();
        assertFalse(config.skip(true, false, true, false));
        assertTrue(config.skip(true, false, true, true));
        assertFalse(config.skip(true, false, false, true), "其他分类不应受动物规则影响");
    }

    @Test void otherDimensionsAndDisabledPolicyKeepAllSpawnCategoriesUnchanged() {
        NaturalSpawnConfig config = new NaturalSpawnConfig();
        for (boolean monster : new boolean[]{false, true}) {
            for (boolean animal : new boolean[]{false, true}) {
                for (boolean roll : new boolean[]{false, true}) {
                    assertFalse(config.skip(false, monster, animal, roll));
                }
            }
        }
        config.enabled = false;
        for (boolean roll : new boolean[]{false, true}) {
            assertFalse(config.skip(true, true, false, roll));
            assertFalse(config.skip(true, false, true, roll));
        }
    }

    @Test void missingConfigIsCreatedEnabledAndCommandChangesSurviveReload() throws Exception {
        Path path = directory.resolve("natural_spawning.json");
        NaturalSpawnManager manager = NaturalSpawnManager.get();
        manager.load(path);
        assertTrue(manager.enabled());
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).contains("true"));
        manager.setEnabled(false);
        manager.reload();
        assertFalse(manager.enabled());
        manager.setEnabled(true);
        manager.reload();
        assertTrue(manager.enabled());
    }

    @Test void legacyEmptyConfigAlsoDefaultsToEnabled() throws Exception {
        Path path = directory.resolve("natural_spawning.json");
        Files.writeString(path, "{}", StandardCharsets.UTF_8);
        NaturalSpawnManager.get().load(path);
        assertTrue(NaturalSpawnManager.get().enabled());
    }

    @Test void malformedReloadRetainsLivePolicyAndOriginalFile() throws Exception {
        Path path = directory.resolve("natural_spawning.json");
        NaturalSpawnManager manager = NaturalSpawnManager.get();
        manager.load(path);
        manager.setEnabled(false);
        Files.writeString(path, "{broken", StandardCharsets.UTF_8);
        assertThrows(IOException.class, manager::reload);
        assertFalse(manager.enabled());
        assertEquals("{broken", Files.readString(path, StandardCharsets.UTF_8));
    }

    @Test void failedSaveDoesNotChangeLivePolicy() throws Exception {
        Path path = directory.resolve("natural_spawning.json");
        NaturalSpawnManager manager = NaturalSpawnManager.get();
        manager.load(path);
        Files.delete(path);
        Files.createDirectory(path);
        Files.writeString(path.resolve("block-replacement"), "occupied", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> manager.setEnabled(false));
        assertTrue(manager.enabled());
    }
}
