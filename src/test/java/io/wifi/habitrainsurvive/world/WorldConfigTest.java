package io.wifi.habitrainsurvive.world;

import com.google.gson.Gson;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldConfigTest {
    private static final Gson GSON = new Gson();

    @Test void sreNoLongerNeedsSpecialTrainingDimensionFiltering() {
        for (int slot = 0; slot < 8; slot++)
            assertFalse(WorldConfig.excludedId("starrailexpress:training_" + slot));
        assertFalse(WorldConfig.excludedId("starrailexpress:training"));
        assertTrue(WorldConfig.excludedId("habi_train_survive:resource"));
        assertTrue(WorldConfig.excludedId("habi_train_survive:resource_nether"));
        assertFalse(WorldConfig.excludedId("minecraft:overworld"));
        assertFalse(WorldConfig.excludedId("starrailexpress:backworld"));
    }

    @Test void defaultsContainFiveWorldsIncludingPlots() {
        WorldConfig config = WorldConfig.defaults();
        config.validate();
        assertEquals(5, config.worlds.size());
        assertEquals(5, config.worlds.stream().filter(w -> w.enabled).count());
        assertEquals(Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "habi_train_survive:hub", "habi_train_survive:plots"),
                config.worlds.stream().map(w -> w.id).collect(Collectors.toSet()));
        WorldDefinition hub = config.worlds.stream().filter(w -> w.kind.equals("hub")).findFirst().orElseThrow();
        assertTrue(hub.enabled);
        assertEquals("void", hub.preset);
        assertNotNull(hub.spawn);
        assertEquals(hub.id, hub.spawn.dimension);
        assertFalse(hub.build || hub.interact || hub.pvp || hub.damage || hub.hunger || hub.mobs
                || hub.mobGrief || hub.explosions || hub.fireSpread || hub.fluids || hub.pistons
                || hub.allowClaims || hub.allowShops || hub.weather || hub.portals);
        assertEquals(0, config.worlds.stream().filter(WorldDefinition::resource).count());
    }

    @Test void oldWorldConfigsGainPlotWorldWithoutChangingExistingWorlds() {
        WorldConfig config = WorldConfig.defaults();
        config.worlds.removeIf(w -> w.id.equals("habi_train_survive:plots"));
        WorldDefinition existing = config.worlds.getFirst();
        existing.name = "Keep my world";
        assertTrue(config.ensurePlotWorld());
        assertFalse(config.ensurePlotWorld());
        assertSame(existing, config.worlds.getFirst());
        assertEquals("Keep my world", existing.name);
        config.validate();
        WorldDefinition plots = config.worlds.stream().filter(w -> w.id.equals("habi_train_survive:plots")).findFirst().orElseThrow();
        assertEquals("flat", plots.preset);
        assertFalse(plots.allowClaims || plots.pvp || plots.explosions || plots.fireSpread || plots.mobGrief);
        assertTrue(plots.build && plots.allowShops);
        assertEquals(-60, plots.spawn.y);
    }

    @Test void resourceIdsRejectInvalidGrammarAndTraversalOrEmptyComponents() {
        for (String id : new String[] {null, "", "world", ":world", "namespace:", "namespace:world:other",
                "Namespace:world", "namespace:World", "name space:world", "namespace:world name",
                "name/space:world", "namespace:world\\child", "namespace:world\u0000",
                ".:world", "..:world", "namespace:/world", "namespace:world/", "namespace:world//child",
                "namespace:.", "namespace:..", "namespace:./world", "namespace:world/../child"}) {
            assertThrows(IllegalArgumentException.class, () -> WorldDefinition.validateId(id), String.valueOf(id));
        }
    }

    @Test void resourceIdsRejectWindowsDeviceStemsAndTrailingDotAliasesInEverySegment() {
        List<String> aliases = new ArrayList<>(List.of("con", "prn", "aux", "nul"));
        for (int i = 1; i <= 9; i++) aliases.addAll(List.of("com" + i, "lpt" + i));
        for (String alias : aliases) {
            for (String suffix : List.of("", ".mca", ".foo.bar")) rejectSegment(alias + suffix);
        }
        for (String alias : List.of("world.", "world..", ".", "..", "...")) rejectSegment(alias);
    }

    private static void rejectSegment(String segment) {
        for (String id : List.of(segment + ":world", "habi_train_survive:" + segment,
                "habi_train_survive:maps/" + segment + "/world")) {
            assertThrows(IllegalArgumentException.class, () -> WorldDefinition.validateId(id), id);
        }
    }

    @Test void safeNativeResourceIdCharactersAndDeviceLookalikesRemainAllowed() {
        for (String id : List.of("minecraft:overworld", "example.namespace-1:maps/nested-world_2.v3",
                "_namespace:.hidden/_world", "example..namespace:maps/a..b", "conifer:console",
                "com0:com10/lpt0/lpt10/auxiliary/prn_map/nulled", "namespace:con_.mca")) {
            assertDoesNotThrow(() -> WorldDefinition.validateId(id), id);
        }
    }

    @Test void configRejectsDuplicateAndNestedWorldDirectories() {
        WorldConfig duplicates = WorldConfig.defaults();
        duplicates.worlds.add(new WorldDefinition("habi_train_survive:hub", "Duplicate", "normal", "survival"));
        assertTrue(assertThrows(IllegalArgumentException.class, duplicates::validate).getMessage().contains("重复世界ID"));
        WorldConfig nested = WorldConfig.defaults();
        nested.worlds.add(new WorldDefinition("habi_train_survive:hub/child", "Nested", "normal", "survival"));
        assertTrue(assertThrows(IllegalArgumentException.class, nested::validate).getMessage().contains("世界目录不能嵌套"));
    }

    @Test void portalTargetsAndPersistedStateIdsUseTheSameFilesystemSafeValidation() {
        WorldDefinition world = new WorldDefinition("habi_train_survive:custom", "Custom", "normal", "survival");
        world.nether = "aux:world";
        assertThrows(IllegalArgumentException.class, world::validate);
        WorldState state = new WorldState();
        state.world("habi_train_survive:resource.");
        assertThrows(IllegalArgumentException.class, state::validate);
    }

    @Test void oldDefinitionJsonKeepsSafeOptionalDefaults() {
        WorldDefinition world = GSON.fromJson("""
                {"id":"habi_train_survive:custom","name":"Legacy"}
                """, WorldDefinition.class);
        world.validate();
        assertTrue(world.enabled && world.open);
        assertEquals("normal", world.preset);
        assertEquals("survival", world.kind);
        assertEquals("", world.permission);
        assertNull(world.seed);
        assertNull(world.spawn);
        assertNull(world.importSource);
        assertEquals(0, world.entryFee);
        assertEquals(0, world.playerLimit);
        assertEquals(0, world.resetIntervalHours);
        assertEquals("minecraft:overworld", world.overworld);
        assertEquals("minecraft:the_nether", world.nether);
        assertEquals("minecraft:the_end", world.end);
    }

    @Test void oldRuntimeJsonDoesNotQueueResetImportOrHubConstruction() {
        WorldState state = GSON.fromJson("""
                {"worlds":{"habi_train_survive:resource":{"lastReset":1234}},"lastVisits":{}}
                """, WorldState.class);
        state.validate();
        WorldState.Runtime runtime = state.world("habi_train_survive:resource");
        assertEquals(1234, runtime.lastReset);
        assertEquals(0, runtime.generation);
        assertEquals(0, runtime.dayTime);
        assertFalse(runtime.pendingReset || runtime.imported || runtime.hubBuilt || runtime.resetHadDirectory);
        assertEquals(0, runtime.hubBuildCursor);
        assertEquals(0, runtime.resetGeneration);
        assertNull(runtime.resetBackup);
        assertNull(runtime.spawn);
        assertTrue(state.lastVisits.isEmpty());
        assertNotNull(GSON.fromJson("{}", WorldState.class).lastVisits);
    }

    @Test void removedResourcePositionsAreAlwaysInvalid() {
        GlobalPosInfo legacy = GSON.fromJson("""
                {"dimension":"habi_train_survive:resource","x":1,"y":80,"z":2,"yaw":0,"pitch":0}
                """, GlobalPosInfo.class);
        assertEquals(0, legacy.generation);
        WorldManager manager = WorldManager.get();
        WorldState.Runtime runtime = manager.runtime(legacy.dimension);
        long previous = runtime.generation;
        try {
            runtime.generation = 0;
            assertFalse(manager.current(legacy));
            runtime.generation = 1;
            assertFalse(manager.current(legacy));
            GlobalPosInfo current = new GlobalPosInfo(legacy.dimension, 1, 80, 2, 0, 0);
            assertFalse(manager.current(current));
            assertFalse(manager.current(null));
        } finally {
            runtime.generation = previous;
        }
    }

    @Test void missingPositionDimensionsFailClosedWithoutAnOverworldFallback() {
        for (String dimension : new String[] {null, ""}) {
            GlobalPosInfo position = new GlobalPosInfo();
            position.dimension = dimension;
            assertNull(position.resolveLevel(null));
        }
    }
}
