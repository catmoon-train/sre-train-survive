package io.wifi.habitrainsurvive.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Mutable authoring JSON isolated from its immutable runtime snapshot. Save performs full validation. */
public final class MenuDraft {
    private static final Set<String> BUILT_INS = Set.of("main", "worlds", "homes", "warps", "towns", "players", "admin");
    private final MenuConfig base;
    private final JsonObject original;
    private final JsonObject draft;

    public MenuDraft(MenuConfig base) {
        this.base = Objects.requireNonNull(base, "base");
        original = MenuConfig.toJson(base);
        draft = original.deepCopy();
    }

    public MenuConfig base() { return base; }
    public JsonObject json() { return draft.deepCopy(); }
    public boolean dirty() { return !draft.equals(original); }
    public List<String> menuIds() { return menus().keySet().stream().sorted().toList(); }

    /** Live page object, intentionally allowing temporarily incomplete GUI edits. */
    public JsonObject page(String id) {
        JsonObject page = menus().getAsJsonObject(id);
        if (page == null) throw new IllegalArgumentException("菜单不存在: " + id);
        return page;
    }

    public String create(String id) {
        checkNewId(id);
        JsonObject page = new JsonObject();
        page.addProperty("title", "自定义菜单 · " + id);
        page.addProperty("rows", 3);
        page.addProperty("permission", "");
        page.addProperty("back", "");
        page.addProperty("dynamic", "");
        JsonArray buttons = new JsonArray();
        buttons.add(newButton(22, "minecraft:barrier", "关闭", "CLOSE", ""));
        page.add("buttons", buttons);
        menus().add(id, page);
        return id;
    }

    public String copy(String source, String newId) {
        JsonObject sourcePage = page(source);
        checkNewId(newId);
        JsonObject copied = sourcePage.deepCopy();
        copied.addProperty("title", text(sourcePage, "title") + " 副本");
        menus().add(newId, copied);
        return newId;
    }

    public String rename(String old, String newId) {
        JsonObject renamed = page(old);
        checkProtected(old);
        MenuConfig.menuId(newId, "newId");
        checkProtected(newId);
        if (menus().has(newId)) throw new IllegalArgumentException("菜单 ID 已存在: " + newId);
        // Collect every patch before modifying anything, even when actions are incomplete.
        List<JsonObject> backs = new ArrayList<>();
        List<JsonObject> opens = new ArrayList<>();
        for (String id : menuIds()) {
            JsonObject page = page(id);
            if (old.equals(text(page, "back"))) backs.add(page);
            for (JsonObject action : openActions(page)) {
                if (old.equals(text(action, "value"))) opens.add(action);
            }
        }
        menus().remove(old);
        menus().add(newId, renamed);
        if (old.equals(text(draft, "defaultMenu"))) draft.addProperty("defaultMenu", newId);
        backs.forEach(page -> page.addProperty("back", newId));
        opens.forEach(action -> action.addProperty("value", newId));
        return newId;
    }

    public void delete(String id) {
        page(id);
        checkProtected(id);
        if (id.equals(text(draft, "defaultMenu"))) throw new IllegalArgumentException("不能删除默认菜单: " + id);
        List<String> references = new ArrayList<>();
        for (String source : menuIds()) {
            JsonObject page = page(source);
            if (id.equals(text(page, "back"))) references.add(source + " (back)");
            for (JsonObject action : openActions(page)) {
                if (id.equals(text(action, "value"))) references.add(source + " (OPEN)");
            }
        }
        if (!references.isEmpty()) throw new IllegalArgumentException("菜单 " + id + " 仍被引用: " + String.join(", ", references));
        menus().remove(id);
    }

    /** Returns a live button, or null for an unoccupied slot. */
    public JsonObject button(String id, int slot) {
        JsonObject page = page(id);
        checkSlot(page, slot);
        return findButton(page, slot);
    }

    public JsonObject addButton(String id, int slot) {
        JsonObject page = editablePage(id);
        checkSlot(page, slot);
        checkEmpty(page, slot);
        JsonObject button = newButton(slot, "minecraft:paper", "新按钮", "MESSAGE", "请编辑按钮动作");
        page.getAsJsonArray("buttons").add(button);
        layoutChanged(id, page);
        return button;
    }

    public void removeButton(String id, int slot) {
        JsonObject page = editablePage(id);
        checkSlot(page, slot);
        JsonObject button = requireButton(page, slot);
        page.getAsJsonArray("buttons").remove(button);
        layoutChanged(id, page);
    }

    public void moveButton(String id, int from, int to, boolean copy) {
        JsonObject page = editablePage(id);
        checkSlot(page, from);
        checkSlot(page, to);
        JsonObject source = requireButton(page, from);
        checkEmpty(page, to);
        JsonObject moved = copy ? source.deepCopy() : source;
        if (copy) page.getAsJsonArray("buttons").add(moved);
        moved.addProperty("slot", to);
        layoutChanged(id, page);
    }

    public void resize(String id, int rows) {
        JsonObject page = page(id);
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("菜单行数必须为 1..6");
        if (generated(page) && rows != 6) throw new IllegalArgumentException("动态列表必须使用 6 行: " + id);
        for (JsonElement entry : page.getAsJsonArray("buttons")) {
            int slot = entry.getAsJsonObject().get("slot").getAsInt();
            if (slot < 0 || slot >= rows * 9) throw new IllegalArgumentException("菜单 " + id + " 的按钮槽位 " + slot + " 超出新行数，请先移动或删除");
        }
        if (page.get("rows").getAsInt() == rows) return;
        page.addProperty("rows", rows);
        layoutChanged(id, page);
    }

    public void setDefaultMenu(String id) {
        page(id);
        draft.addProperty("defaultMenu", id);
    }

    public void setSneakSwap(boolean enabled) { draft.addProperty("sneakSwapEnabled", enabled); }
    public void setAutoShortcuts(boolean enabled) { draft.addProperty("autoShortcuts", enabled); }

    private JsonObject menus() { return draft.getAsJsonObject("menus"); }

    private void checkNewId(String id) {
        MenuConfig.menuId(id, "id");
        if (menus().has(id)) throw new IllegalArgumentException("菜单 ID 已存在: " + id);
        if (menus().size() >= 128) throw new IllegalArgumentException("菜单数量不能超过 128");
    }

    private static void checkProtected(String id) {
        if (BUILT_INS.contains(id)) throw new IllegalArgumentException("不能重命名或删除内置菜单: " + id);
    }

    private JsonObject editablePage(String id) {
        JsonObject page = page(id);
        if (generated(page)) throw new IllegalArgumentException("动态列表按钮由服务器生成，不能编辑: " + id);
        return page;
    }

    private static boolean generated(JsonObject page) {
        String dynamic = text(page, "dynamic");
        return !dynamic.isEmpty() && !dynamic.equals("admin");
    }

    private void layoutChanged(String id, JsonObject page) {
        if (id.equals(text(draft, "defaultMenu")) || text(page, "dynamic").equals("admin")) setAutoShortcuts(false);
    }

    private static void checkSlot(JsonObject page, int slot) {
        if (slot < 0 || slot >= page.get("rows").getAsInt() * 9) throw new IllegalArgumentException("按钮槽位超出菜单范围: " + slot);
    }

    private static JsonObject findButton(JsonObject page, int slot) {
        for (JsonElement entry : page.getAsJsonArray("buttons")) {
            JsonObject button = entry.getAsJsonObject();
            if (button.get("slot").getAsInt() == slot) return button;
        }
        return null;
    }

    private static JsonObject requireButton(JsonObject page, int slot) {
        JsonObject button = findButton(page, slot);
        if (button == null) throw new IllegalArgumentException("槽位没有按钮: " + slot);
        return button;
    }

    private static void checkEmpty(JsonObject page, int slot) {
        if (findButton(page, slot) != null) throw new IllegalArgumentException("目标槽位已被占用: " + slot);
    }

    private static JsonObject newButton(int slot, String icon, String name, String type, String value) {
        JsonObject button = new JsonObject();
        button.addProperty("slot", slot);
        button.addProperty("icon", icon);
        button.addProperty("name", name);
        button.add("lore", new JsonArray());
        button.addProperty("permission", "");
        button.addProperty("amount", 1);
        JsonObject action = new JsonObject();
        action.addProperty("type", type);
        action.addProperty("value", value);
        action.addProperty("permission", "");
        JsonArray actions = new JsonArray();
        actions.add(action);
        button.add("actions", actions);
        return button;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static List<JsonObject> openActions(JsonObject page) {
        List<JsonObject> opens = new ArrayList<>();
        JsonElement buttons = page.get("buttons");
        if (buttons == null || !buttons.isJsonArray()) return opens;
        for (JsonElement button : buttons.getAsJsonArray()) {
            if (!button.isJsonObject()) continue;
            JsonElement actions = button.getAsJsonObject().get("actions");
            if (actions == null || !actions.isJsonArray()) continue;
            for (JsonElement action : actions.getAsJsonArray()) {
                if (action.isJsonObject() && text(action.getAsJsonObject(), "type").equals("OPEN")) opens.add(action.getAsJsonObject());
            }
        }
        return opens;
    }
}
