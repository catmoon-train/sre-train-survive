package io.wifi.habitrainsurvive.presentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PresentationConfigTest {
    @TempDir Path directory;

    @Test void bundledConfigurationHasLowFrequencyRefreshAndRequiredSidebarFields() throws Exception {
        Path file = directory.resolve("presentation.json");
        ConfigFiles.installDefault(file, "/defaults/presentation.json");
        var config = ConfigFiles.read(file, PresentationConfig.class);
        config.validate();
        assertTrue(config.refreshSeconds >= 2);
        for (String placeholder : List.of("{balance}", "{world}", "{group}", "{online}")) {
            assertTrue(config.sidebarLines.stream().anyMatch(line -> line.contains(placeholder)));
        }
        assertTrue(config.tabHeader.contains("{group}") || config.tabFooter.contains("{group}"));
    }

    @Test void invalidFrequenciesAndOversizedSidebarAreRejected() {
        var config = new PresentationConfig();
        config.refreshSeconds = 1;
        assertThrows(IllegalArgumentException.class, config::validate);
        config.refreshSeconds = 5;
        config.announcementIntervalSeconds = 0;
        assertThrows(IllegalArgumentException.class, config::validate);
        config.announcementIntervalSeconds = 180;
        config.sidebarLines = java.util.Collections.nCopies(16, "line");
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test void preferencesRoundTripSeparatelyFromCmiProfiles() throws Exception {
        var state = new PresentationState();
        UUID uuid = UUID.randomUUID();
        state.players.put(uuid, new PresentationState.Preferences(false, false, false));
        Path file = directory.resolve("preferences.json");
        ConfigFiles.write(file, state);
        var restored = ConfigFiles.read(file, PresentationState.class);
        restored.validate();
        assertFalse(restored.players.get(uuid).sidebar);
        assertFalse(restored.players.get(uuid).announcements);
        assertFalse(restored.players.get(uuid).sounds);
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("kitCooldowns"));
    }

    @Test void invalidJsonIsReportedInsteadOfReplacedByDefaults() throws Exception {
        Path file = directory.resolve("invalid.json");
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ConfigFiles.read(file, PresentationConfig.class));
        assertEquals("{broken", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test void defaultInstallDoesNotOverwriteAdministratorChanges() throws Exception {
        Path file = directory.resolve("presentation.json");
        Files.writeString(file, "{\"refreshSeconds\":10}", StandardCharsets.UTF_8);
        ConfigFiles.installDefault(file, "/defaults/presentation.json");
        var config = ConfigFiles.read(file, PresentationConfig.class);
        config.validate();
        assertEquals(10, config.refreshSeconds);
    }

    @Test void malformedPreferencesAreRejectedWithoutReset() {
        var state = new PresentationState();
        state.players.put(UUID.randomUUID(), null);
        assertThrows(IllegalArgumentException.class, state::validate);
    }
}
