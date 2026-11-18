package io.wifi.habitrainsurvive.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MenuDraftTest {
    private static MenuConfig parse(JsonObject json) throws IOException {
        return MenuConfig.read(new StringReader(json.toString()));
    }

    private static JsonObject oldJson() {
        return JsonParser.parseString("""
                {"version":1,"defaultMenu":"main","menus":{"main":{"title":"Main","rows":3,
                "buttons":[{"slot":0,"icon":"minecraft:stone","name":"Button","actions":[{"type":"CLOSE"}]}]}}}
                """).getAsJsonObject();
    }

    private static JsonObject firstButton(JsonObject json) {
        return json.getAsJsonObject("menus").getAsJsonObject("main").getAsJsonArray("buttons").get(0).getAsJsonObject();
    }

    private static MenuConfig base() {
        MenuDefinition.Entry close = new MenuDefinition.Entry(0, "minecraft:stone", "Button", List.of(), "",
                List.of(new MenuAction(MenuAction.Type.CLOSE, "", "", 1, 1)));
        Map<String, MenuDefinition> menus = new LinkedHashMap<>();
        menus.put("main", new MenuDefinition("main", "Main", 3, "", "", "", List.of(close)));
        menus.put("custom", new MenuDefinition("custom", "Custom", 3, "", "", "", List.of()));
        for (String id : List.of("worlds", "homes", "warps", "towns", "players", "admin")) {
            menus.put(id, new MenuDefinition(id, id, 6, "", "", id, List.of()));
        }
        return new MenuConfig(false, "main", menus);
    }

    private static JsonObject action(String type, String value) {
        JsonObject action = new JsonObject();
        action.addProperty("type", type);
        action.addProperty("value", value);
        return action;
    }

    private static void assertUnchanged(MenuDraft draft, Runnable invalid) {
        JsonObject before = draft.json();
        assertThrows(IllegalArgumentException.class, invalid::run);
        assertEquals(before, draft.json());
    }

    @Test void legacyJsonAndConstructorsDefaultNewFields() throws Exception {
        MenuConfig parsed = parse(oldJson());
        assertTrue(parsed.autoShortcuts());
        assertEquals(1, parsed.menus().get("main").buttons().getFirst().amount());
        assertTrue(base().autoShortcuts());
        assertEquals(1, base().menus().get("main").buttons().getFirst().amount());
    }

    @Test void amountAndShortcutSettingsRoundTripWithAllSchemaFields() throws Exception {
        JsonObject json = oldJson();
        json.addProperty("autoShortcuts", false);
        json.addProperty("sneakSwapEnabled", true);
        firstButton(json).addProperty("amount", 64);
        // SOUND alone carries volume/pitch; other actions must not gain these fields.
        JsonArray actions = new JsonArray();
        JsonObject sound = action("SOUND", "minecraft:ui.button.click");
        sound.addProperty("volume", 0.5);
        sound.addProperty("pitch", 1.5);
        actions.add(sound);
        actions.add(action("CLOSE", ""));
        firstButton(json).add("actions", actions);
        MenuConfig parsed = parse(json);
        JsonObject serialized = MenuConfig.toJson(parsed);
        assertFalse(parsed.autoShortcuts());
        assertEquals(64, parsed.menus().get("main").buttons().getFirst().amount());
        assertEquals(parsed, parse(serialized));
        assertEquals(serialized.toString(), MenuConfig.toJson(parsed).toString());
        JsonObject page = serialized.getAsJsonObject("menus").getAsJsonObject("main");
        assertTrue(page.has("permission") && page.has("back") && page.has("dynamic"));
        assertTrue(firstButton(serialized).has("lore") && firstButton(serialized).has("permission"));
        assertTrue(firstButton(serialized).getAsJsonArray("actions").get(0).getAsJsonObject().has("volume"));
        assertFalse(firstButton(serialized).getAsJsonArray("actions").get(1).getAsJsonObject().has("volume"));
        assertEquals(base(), parse(MenuConfig.toJson(base())));
    }

    @Test void rejectsWrongTypesAndAmountOutsideIntegerRange() {
        for (var invalid : List.of(JsonNull.INSTANCE, JsonParser.parseString("\"1\""), JsonParser.parseString("true"),
                JsonParser.parseString("0"), JsonParser.parseString("65"), JsonParser.parseString("1.5"))) {
            JsonObject json = oldJson();
            firstButton(json).add("amount", invalid);
            assertThrows(IllegalArgumentException.class, () -> parse(json));
        }
        for (var invalid : List.of(JsonNull.INSTANCE, JsonParser.parseString("\"false\""), JsonParser.parseString("1"))) {
            JsonObject json = oldJson();
            json.add("autoShortcuts", invalid);
            assertThrows(IllegalArgumentException.class, () -> parse(json));
        }
    }

    @Test void liveDraftEditsAreIsolatedAndDirtyIsStructural() {
        MenuConfig base = base();
        JsonObject original = MenuConfig.toJson(base);
        MenuDraft draft = new MenuDraft(base);
        assertSame(base, draft.base());
        assertFalse(draft.dirty());
        draft.json().getAsJsonObject("menus").getAsJsonObject("main").addProperty("title", "Not live");
        MenuConfig.toJson(base).getAsJsonObject("menus").remove("main");
        assertFalse(draft.dirty());
        JsonObject page = draft.page("main");
        page.addProperty("title", "Changed");
        assertTrue(draft.dirty());
        page.addProperty("title", "Main");
        assertFalse(draft.dirty());
        draft.button("main", 0).addProperty("name", "Changed button");
        assertTrue(draft.dirty());
        draft.button("main", 0).addProperty("name", "Button");
        assertFalse(draft.dirty());
        assertEquals(original, MenuConfig.toJson(base));
    }

    @Test void createsExpectedStaticPageAndValidatesIdsWithoutPartialChanges() throws Exception {
        MenuDraft draft = new MenuDraft(base());
        assertEquals("new_menu", draft.create("new_menu"));
        JsonObject page = draft.page("new_menu");
        assertEquals("自定义菜单 · new_menu", page.get("title").getAsString());
        assertEquals(3, page.get("rows").getAsInt());
        for (String field : List.of("permission", "back", "dynamic")) assertEquals("", page.get(field).getAsString());
        assertEquals(1, page.getAsJsonArray("buttons").size());
        JsonObject close = draft.button("new_menu", 22);
        assertEquals("minecraft:barrier", close.get("icon").getAsString());
        assertEquals("关闭", close.get("name").getAsString());
        assertEquals(1, close.get("amount").getAsInt());
        assertEquals("CLOSE", close.getAsJsonArray("actions").get(0).getAsJsonObject().get("type").getAsString());
        for (String id : List.of("new_menu", "Bad", "", "bad space", "x".repeat(65))) assertUnchanged(draft, () -> draft.create(id));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> draft.page("missing")).getMessage().contains("missing"));
        assertEquals(draft.menuIds().stream().sorted().toList(), draft.menuIds());
        assertDoesNotThrow(() -> parse(draft.json()));
    }

    @Test void enforcesPageLimitForCreateAndCopyButAllowsRename() {
        MenuDraft draft = new MenuDraft(base());
        for (int n = draft.menuIds().size(); n < 128; n++) draft.create("page_" + n);
        assertUnchanged(draft, () -> draft.create("overflow"));
        assertUnchanged(draft, () -> draft.copy("main", "overflow"));
        assertEquals("renamed", draft.rename("custom", "renamed"));
        assertEquals(128, draft.menuIds().size());
    }

    @Test void copiesAreDeepAndKeepDynamicTypeAndAreDeletable() {
        MenuDraft draft = new MenuDraft(base());
        assertEquals("main_copy", draft.copy("main", "main_copy"));
        assertTrue(draft.page("main_copy").get("title").getAsString().endsWith("副本"));
        draft.button("main_copy", 0).getAsJsonArray("lore").add("Copy only");
        assertTrue(draft.button("main", 0).getAsJsonArray("lore").isEmpty());
        draft.copy("players", "players_copy");
        assertEquals("players", draft.page("players_copy").get("dynamic").getAsString());
        assertUnchanged(draft, () -> draft.addButton("players_copy", 0));
        draft.copy("admin", "admin_copy");
        draft.addButton("admin_copy", 0);
        draft.delete("main_copy");
        draft.delete("players_copy");
        draft.delete("admin_copy");
        assertFalse(draft.menuIds().contains("main_copy"));
        assertUnchanged(draft, () -> draft.copy("main", "custom"));
        assertUnchanged(draft, () -> draft.copy("missing", "another"));
    }

    @Test void renamePatchesDefaultBackAndEveryOpenReference() throws Exception {
        MenuDraft draft = new MenuDraft(base());
        draft.setDefaultMenu("custom");
        draft.page("main").addProperty("back", "custom");
        JsonArray actions = draft.button("main", 0).getAsJsonArray("actions");
        actions.set(0, action("OPEN", "custom"));
        assertEquals("renamed", draft.rename("custom", "renamed"));
        assertEquals("renamed", draft.json().get("defaultMenu").getAsString());
        assertEquals("renamed", draft.page("main").get("back").getAsString());
        assertEquals("renamed", actions.get(0).getAsJsonObject().get("value").getAsString());
        assertFalse(draft.menuIds().contains("custom"));
        assertDoesNotThrow(() -> parse(draft.json()));
        assertUnchanged(draft, () -> draft.rename("renamed", "main"));
        assertUnchanged(draft, () -> draft.rename("renamed", "Bad"));
    }

    @Test void protectedBuiltInsCannotBeRenamedOrDeleted() {
        MenuDraft draft = new MenuDraft(base());
        for (String id : List.of("main", "worlds", "homes", "warps", "towns", "players", "admin")) {
            assertUnchanged(draft, () -> draft.rename(id, "replacement"));
            assertUnchanged(draft, () -> draft.delete(id));
        }
    }

    @Test void deletionReportsIncomingBackAndOpenIdsAndRejectsDefault() {
        MenuDraft draft = new MenuDraft(base());
        draft.page("main").addProperty("back", "custom");
        draft.copy("main", "referrer");
        draft.button("referrer", 0).getAsJsonArray("actions").set(0, action("OPEN", "custom"));
        JsonObject before = draft.json();
        String message = assertThrows(IllegalArgumentException.class, () -> draft.delete("custom")).getMessage();
        assertTrue(message.contains("main") && message.contains("back") && message.contains("referrer") && message.contains("OPEN"));
        assertEquals(before, draft.json());
        draft.page("main").addProperty("back", "");
        draft.page("referrer").addProperty("back", "");
        draft.removeButton("referrer", 0);
        draft.setDefaultMenu("custom");
        assertUnchanged(draft, () -> draft.delete("custom"));
        draft.setDefaultMenu("main");
        draft.delete("custom");
        assertFalse(draft.menuIds().contains("custom"));
    }

    @Test void addAndRemoveButtonsUseLiveDefaultsAndRejectConflictsAndBounds() {
        MenuDraft draft = new MenuDraft(base());
        assertNull(draft.button("custom", 0));
        JsonObject button = draft.addButton("custom", 0);
        assertSame(button, draft.button("custom", 0));
        assertEquals("minecraft:paper", button.get("icon").getAsString());
        assertEquals("新按钮", button.get("name").getAsString());
        assertTrue(button.getAsJsonArray("lore").isEmpty());
        assertEquals("", button.get("permission").getAsString());
        assertEquals(1, button.get("amount").getAsInt());
        assertEquals("MESSAGE", button.getAsJsonArray("actions").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("请编辑按钮动作", button.getAsJsonArray("actions").get(0).getAsJsonObject().get("value").getAsString());
        assertUnchanged(draft, () -> draft.addButton("custom", 0));
        assertUnchanged(draft, () -> draft.addButton("custom", -1));
        assertUnchanged(draft, () -> draft.addButton("custom", 27));
        draft.removeButton("custom", 0);
        assertNull(draft.button("custom", 0));
        assertUnchanged(draft, () -> draft.removeButton("custom", 0));
    }

    @Test void moveRejectsOccupiedTargetsAndCopyDeepCopiesNestedData() {
        MenuDraft draft = new MenuDraft(base());
        JsonObject source = draft.addButton("custom", 0);
        draft.addButton("custom", 1);
        assertUnchanged(draft, () -> draft.moveButton("custom", 0, 1, false));
        assertUnchanged(draft, () -> draft.moveButton("custom", 0, 1, true));
        assertUnchanged(draft, () -> draft.moveButton("custom", 0, 0, false));
        assertUnchanged(draft, () -> draft.moveButton("custom", 0, 27, false));
        assertUnchanged(draft, () -> draft.moveButton("custom", 4, 5, false));
        draft.moveButton("custom", 0, 2, true);
        draft.button("custom", 2).getAsJsonArray("actions").get(0).getAsJsonObject().addProperty("value", "Copy only");
        draft.button("custom", 2).getAsJsonArray("lore").add("Copy lore");
        assertNotEquals(source.getAsJsonArray("actions"), draft.button("custom", 2).getAsJsonArray("actions"));
        assertTrue(source.getAsJsonArray("lore").isEmpty());
        draft.moveButton("custom", 0, 3, false);
        assertNull(draft.button("custom", 0));
        assertSame(source, draft.button("custom", 3));
    }

    @Test void generatedPagesRejectButtonMutationsAndNonSixRowResize() {
        MenuDraft draft = new MenuDraft(base());
        for (String id : List.of("worlds", "homes", "warps", "towns", "players")) {
            assertUnchanged(draft, () -> draft.addButton(id, 0));
            assertUnchanged(draft, () -> draft.removeButton(id, 0));
            assertUnchanged(draft, () -> draft.moveButton(id, 0, 1, false));
            assertUnchanged(draft, () -> draft.moveButton(id, 0, 1, true));
            assertUnchanged(draft, () -> draft.resize(id, 5));
            draft.resize(id, 6);
        }
        assertFalse(draft.dirty());
    }

    @Test void resizeRefusesTruncationAndInvalidRowsBeforeChangingAnything() {
        MenuDraft draft = new MenuDraft(base());
        draft.create("resizable");
        assertUnchanged(draft, () -> draft.resize("resizable", 2));
        assertUnchanged(draft, () -> draft.resize("resizable", 0));
        assertUnchanged(draft, () -> draft.resize("resizable", 7));
        draft.moveButton("resizable", 22, 8, false);
        draft.resize("resizable", 1);
        assertEquals(1, draft.page("resizable").get("rows").getAsInt());
        draft.resize("resizable", 6);
        assertEquals(6, draft.page("resizable").get("rows").getAsInt());
    }

    @Test void layoutEditsSuppressShortcutsOnlyForDefaultOrAdmin() {
        MenuDraft draft = new MenuDraft(base());
        draft.addButton("custom", 0);
        assertTrue(draft.json().get("autoShortcuts").getAsBoolean());
        draft.removeButton("main", 0);
        assertFalse(draft.json().get("autoShortcuts").getAsBoolean());
        draft.setAutoShortcuts(true);
        draft.addButton("main", 0);
        assertFalse(draft.json().get("autoShortcuts").getAsBoolean());
        draft.setAutoShortcuts(true);
        draft.moveButton("main", 0, 1, true);
        assertFalse(draft.json().get("autoShortcuts").getAsBoolean());
        draft.setAutoShortcuts(true);
        draft.resize("main", 4);
        assertFalse(draft.json().get("autoShortcuts").getAsBoolean());
        draft.setAutoShortcuts(true);
        draft.addButton("admin", 0);
        assertFalse(draft.json().get("autoShortcuts").getAsBoolean());
        draft.setAutoShortcuts(true);
        draft.removeButton("admin", 0);
        assertFalse(draft.json().get("autoShortcuts").getAsBoolean());
        draft.setAutoShortcuts(true);
        draft.resize("admin", 3);
        assertFalse(draft.json().get("autoShortcuts").getAsBoolean());
        draft.setAutoShortcuts(true);
        draft.setDefaultMenu("custom");
        draft.moveButton("custom", 0, 1, false);
        assertFalse(draft.json().get("autoShortcuts").getAsBoolean());
    }

    @Test void settingsDirtyStateTogglesAndInvalidDefaultLeavesDraftUntouched() {
        MenuDraft draft = new MenuDraft(base());
        draft.setSneakSwap(true);
        assertTrue(draft.dirty());
        draft.setSneakSwap(false);
        assertFalse(draft.dirty());
        draft.setAutoShortcuts(false);
        assertTrue(draft.dirty());
        draft.setAutoShortcuts(true);
        assertFalse(draft.dirty());
        draft.setDefaultMenu("custom");
        assertTrue(draft.dirty());
        draft.setDefaultMenu("main");
        assertFalse(draft.dirty());
        assertUnchanged(draft, () -> draft.setDefaultMenu("missing"));
    }

    @Test void actionSequencesCanBeTemporarilyInvalidUntilSave() {
        MenuDraft draft = new MenuDraft(base());
        draft.button("main", 0).getAsJsonArray("actions").add(action("MESSAGE", "After close"));
        assertTrue(draft.dirty());
        assertDoesNotThrow(() -> draft.rename("custom", "renamed"));
        assertThrows(IllegalArgumentException.class, () -> parse(draft.json()));
    }
}
