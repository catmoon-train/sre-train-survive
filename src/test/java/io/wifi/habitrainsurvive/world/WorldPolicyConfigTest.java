package io.wifi.habitrainsurvive.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldPolicyConfigTest {
    @Test void vanillaRestrictionsFailInsteadOfSilentlyChangingSharedRules() {
        WorldConfig config = WorldConfig.defaults();
        config.worlds.getFirst().weather = false;
        assertThrows(IllegalArgumentException.class, config::validate);
    }
    @Test void nativeWorldPolicyAllowsIndependentImplementedProtections() {
        WorldConfig config = WorldConfig.defaults();
        WorldDefinition survival = config.worlds.getFirst();
        survival.hunger = survival.pvp = survival.build = survival.fireSpread = false;
        assertDoesNotThrow(config::validate);
    }
    @Test void customWorldCanOwnDifficultyWeatherAndTime() {
        WorldConfig config = WorldConfig.defaults();
        WorldDefinition resource = new WorldDefinition("habi_train_survive:build", "Build", "normal", "survival");
        config.worlds.add(resource);
        resource.mobs = resource.mobGrief = resource.weather = false;
        resource.difficulty = "hard";
        resource.fixedTime = 18000;
        assertDoesNotThrow(config::validate);
    }
    @Test void removedResourceSpawnIsUnavailable() {
        WorldManager manager = WorldManager.get();
        assertNull(manager.definition("habi_train_survive:resource"));
        assertNull(manager.spawn("habi_train_survive:resource"));
        assertFalse(manager.startupEnabled("habi_train_survive:resource"));
    }
    @Test void statusDistinguishesRestartOnlyChangesFromLiveRules() {
        WorldManager manager = WorldManager.get();
        WorldDefinition resource = manager.definition(WorldManager.HUB);
        boolean open = resource.open, enabled = resource.enabled;
        Long seed = resource.seed;
        String preset = resource.preset, source = resource.importSource;
        try {
            assertFalse(manager.restartPending(resource.id));
            resource.open = !open;
            assertFalse(manager.restartPending(resource.id));
            resource.enabled = !enabled;
            assertTrue(manager.restartPending(resource.id));
            resource.enabled = enabled;
            resource.seed = seed == null ? 123L : seed + 1;
            assertTrue(manager.restartPending(resource.id));
            resource.seed = seed;
            resource.preset = "flat";
            assertTrue(manager.restartPending(resource.id));
            resource.preset = preset;
            resource.importSource = "new-template";
            assertTrue(manager.restartPending(resource.id));
        } finally {
            resource.open = open; resource.enabled = enabled; resource.seed = seed;
            resource.preset = preset; resource.importSource = source;
        }
    }
}
