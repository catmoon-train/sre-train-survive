package io.wifi.habitrainsurvive.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** No Minecraft classes are loaded by these configuration tests. */
class MenuConfigTest {
    @TempDir Path directory;

    private static final String BUTTON = """
            {"slot":0,"icon":"minecraft:stone","name":"Button {player}","lore":["{balance}"],
             "actions":[{"type":"CLOSE"}]}
            """;

    private static JsonObject config() {
        return JsonParser.parseString("""
                {"version":1,"defaultMenu":"main","menus":{
                  "main":{"title":"Main","rows":6,"buttons":[%s]}}}
                """.formatted(BUTTON)).getAsJsonObject();
    }

    private static JsonObject page(JsonObject config) { return config.getAsJsonObject("menus").getAsJsonObject("main"); }
    private static JsonObject button(JsonObject config) { return page(config).getAsJsonArray("buttons").get(0).getAsJsonObject(); }
    private static MenuConfig parse(JsonObject json) throws IOException { return MenuConfig.read(new StringReader(json.toString())); }
    private static void rejected(JsonObject json) { assertThrows(IllegalArgumentException.class, () -> parse(json)); }

    @Test void shippedDefaultsAreCompleteAndImmutable() throws Exception {
        try (var stream = MenuConfig.class.getResourceAsStream("/defaults/menus.json")) {
            assertNotNull(stream);
            MenuConfig config = MenuConfig.read(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals("main", config.defaultMenu());
            assertTrue(config.sneakSwapEnabled());
            assertEquals(Set.of("main", "worlds", "homes", "warps", "towns", "players", "admin"), config.menus().keySet());
            var entries = config.menus().get("main").buttons();
            assertTrue(entries.size() >= 24);
            Set<String> commands = entries.stream().flatMap(e -> e.actions().stream())
                    .filter(a -> a.type() == MenuAction.Type.PLAYER_COMMAND).map(MenuAction::value).collect(java.util.stream.Collectors.toSet());
            assertTrue(commands.containsAll(Set.of("hub", "claim", "bal", "shop", "ah", "gift", "kits", "daily",
                    "quest", "lottery", "title", "grave", "guide", "rules", "settings")));
            Set<String> rtpTargets = config.menus().values().stream().flatMap(menu -> menu.buttons().stream())
                    .flatMap(entry -> entry.actions().stream()).filter(action -> action.type() == MenuAction.Type.RTP)
                    .map(MenuAction::value).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("minecraft:overworld"), rtpTargets);
            assertThrows(UnsupportedOperationException.class, () -> config.menus().clear());
            assertThrows(UnsupportedOperationException.class, entries::clear);
            assertThrows(UnsupportedOperationException.class, entries.getFirst().lore()::clear);
            assertThrows(UnsupportedOperationException.class, entries.getFirst().actions()::clear);
        }
    }

    @Test void rejectsUnknownDuplicateNullAndWrongTypedFields() {
        JsonObject unknown = config(); unknown.addProperty("sneekSwapEnabled", true); rejected(unknown);
        JsonObject nullValue = config(); nullValue.add("sneakSwapEnabled", com.google.gson.JsonNull.INSTANCE); rejected(nullValue);
        JsonObject wrongType = config(); wrongType.addProperty("sneakSwapEnabled", "true"); rejected(wrongType);
        JsonObject permission = config(); button(permission).addProperty("permission", "*"); rejected(permission);
        JsonObject version = config(); version.addProperty("version", 2); rejected(version);
        assertThrows(IllegalArgumentException.class, () -> MenuConfig.read(new StringReader(config().toString().replace("\"version\":1", "\"version\":1,\"version\":1"))));
        assertThrows(Exception.class, () -> MenuConfig.read(new StringReader("{unquoted:true}")));
        assertThrows(Exception.class, () -> MenuConfig.read(new StringReader(config() + " {}")));
    }

    @Test void validatesRowsSlotsIconsAndText() {
        for (Number rows : new Number[]{0, 7, 2.5}) { JsonObject json = config(); page(json).addProperty("rows", rows); rejected(json); }
        for (int slot : new int[]{-1, 54}) { JsonObject json = config(); button(json).addProperty("slot", slot); rejected(json); }
        JsonObject duplicate = config(); page(duplicate).getAsJsonArray("buttons").add(button(duplicate).deepCopy()); rejected(duplicate);
        for (String icon : new String[]{"stone", "Minecraft:stone", "minecraft:air", "minecraft:bad space"}) {
            JsonObject json = config(); button(json).addProperty("icon", icon); rejected(json);
        }
        for (String name : new String[]{"", "{unknown}", "broken {player", "name\nnext"}) {
            JsonObject json = config(); button(json).addProperty("name", name); rejected(json);
        }
    }

    @Test void validatesActionTypesTargetsAndSequenceOrder() {
        JsonObject unknownType = config(); button(unknownType).getAsJsonArray("actions").get(0).getAsJsonObject().addProperty("type", "OP"); rejected(unknownType);
        JsonObject missing = config(); setOpen(button(missing), "missing"); rejected(missing);
        JsonObject empty = config(); button(empty).add("actions", new JsonArray()); rejected(empty);
        JsonObject sequence = config();
        button(sequence).getAsJsonArray("actions").add(JsonParser.parseString("{\"type\":\"MESSAGE\",\"value\":\"after close\"}"));
        rejected(sequence);
        JsonObject sound = config(); button(sound).add("actions", JsonParser.parseString("""
                [{"type":"SOUND","value":"minecraft:ui.button.click","volume":0.8,"pitch":1},
                 {"type":"MESSAGE","value":"Balance {balance}"},{"type":"CLOSE"}]
                """));
        assertDoesNotThrow(() -> parse(sound));
    }

    private static void setOpen(JsonObject button, String target) {
        JsonObject action = new JsonObject(); action.addProperty("type", "OPEN"); action.addProperty("value", target);
        JsonArray actions = new JsonArray(); actions.add(action); button.add("actions", actions);
    }

    @Test void rejectsSelfAndIndirectOpenCyclesButAllowsExplicitBackNavigation() {
        JsonObject self = config(); setOpen(button(self), "main"); rejected(self);
        JsonObject indirect = config();
        JsonObject second = page(indirect).deepCopy();
        indirect.getAsJsonObject("menus").add("second", second);
        setOpen(button(indirect), "second");
        setOpen(second.getAsJsonArray("buttons").get(0).getAsJsonObject(), "main");
        rejected(indirect);
        second.add("buttons", new JsonArray());
        second.addProperty("back", "main");
        assertDoesNotThrow(() -> parse(indirect));
        page(indirect).addProperty("back", "second");
        rejected(indirect);
    }

    @Test void rejectsMissingBackAndDynamicShapeMismatch() {
        JsonObject back = config(); page(back).addProperty("back", "missing"); rejected(back);
        JsonObject selfBack = config(); page(selfBack).addProperty("back", "main"); rejected(selfBack);
        JsonObject dynamic = config(); page(dynamic).addProperty("dynamic", "players"); rejected(dynamic);
        page(dynamic).add("buttons", new JsonArray());
        assertDoesNotThrow(() -> parse(dynamic));
        page(dynamic).addProperty("rows", 3); rejected(dynamic);
    }

    @Test void firstLoadCopiesDefaultsAndFailedReloadsPreserveLastGoodSnapshot() throws Exception {
        Path file = directory.resolve("nested/menus.json");
        MenuConfig.Store store = new MenuConfig.Store();
        store.load(file, c -> { });
        assertTrue(Files.isRegularFile(file));
        MenuConfig initial = store.current();
        String valid = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.reload(c -> { }));
        assertSame(initial, store.current());
        Files.writeString(file, valid.replace("\"rows\": 6", "\"rows\": 8"), StandardCharsets.UTF_8);
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () -> store.reload(c -> { }));
        assertTrue(invalid.getMessage().contains("menus.json"));
        assertTrue(invalid.getMessage().contains("rows"));
        assertSame(initial, store.current());
        Files.writeString(file, valid, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> store.reload(c -> { throw new IllegalArgumentException("unknown registry item"); }));
        assertSame(initial, store.current());
        Files.delete(file);
        assertThrows(IOException.class, () -> store.reload(c -> { }));
        assertFalse(Files.exists(file));
        assertSame(initial, store.current());
    }

    @Test void validReloadInstallsNewSnapshotAndInvalidLoadDoesNotChangeRememberedPath() throws Exception {
        Path file = directory.resolve("menus.json");
        Files.writeString(file, config().toString(), StandardCharsets.UTF_8);
        MenuConfig.Store store = new MenuConfig.Store();
        store.load(file, c -> { });
        MenuConfig initial = store.current();
        Path bad = directory.resolve("bad.json");
        Files.writeString(bad, "{}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> store.load(bad, c -> { }));
        assertSame(initial, store.current());
        JsonObject replacement = config(); replacement.addProperty("sneakSwapEnabled", true);
        Files.writeString(file, replacement.toString(), StandardCharsets.UTF_8);
        store.reload(c -> { });
        assertNotSame(initial, store.current());
        assertTrue(store.current().sneakSwapEnabled());
    }
}
