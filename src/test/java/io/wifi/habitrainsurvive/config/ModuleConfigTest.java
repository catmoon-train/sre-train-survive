package io.wifi.habitrainsurvive.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModuleConfigTest {

    @Test
    void defaultsAllModulesEnabledAndPersistsToggles(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("modules.json");
        ModuleConfig.load(file);

        assertTrue(Files.exists(file), "modules.json should be created on first load");
        for (ModuleId id : ModuleId.values()) {
            assertTrue(ModuleConfig.isEnabled(id), "Module " + id.id() + " should default to enabled");
        }

        ModuleConfig.setEnabled(ModuleId.CHEST_SHOP, false);
        assertFalse(ModuleConfig.isEnabled(ModuleId.CHEST_SHOP));

        // Reload from disk and verify persistence
        ModuleConfig.reload();
        assertFalse(ModuleConfig.isEnabled(ModuleId.CHEST_SHOP));
        assertTrue(ModuleConfig.isEnabled(ModuleId.TOWN));

        // Re-enable for clean static state
        ModuleConfig.setEnabled(ModuleId.CHEST_SHOP, true);
        assertTrue(ModuleConfig.isEnabled(ModuleId.CHEST_SHOP));
    }

    @Test
    void missingEntriesInExistingFileDefaultToTrue(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("modules.json");
        Files.writeString(file, "{\"modules\":{\"chestshop\":false}}");
        ModuleConfig.load(file);

        assertFalse(ModuleConfig.isEnabled(ModuleId.CHEST_SHOP));
        assertTrue(ModuleConfig.isEnabled(ModuleId.TOWN));
        assertTrue(ModuleConfig.isEnabled(ModuleId.CLAIM));

        // Restore
        ModuleConfig.setEnabled(ModuleId.CHEST_SHOP, true);
    }
}
