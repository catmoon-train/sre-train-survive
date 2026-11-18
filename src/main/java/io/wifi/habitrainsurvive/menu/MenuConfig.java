package io.wifi.habitrainsurvive.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Strict JSON parsing and transactional storage, with no Minecraft class references. */
public record MenuConfig(boolean sneakSwapEnabled, String defaultMenu, Map<String, MenuDefinition> menus,
                         boolean autoShortcuts) {
    private static final Set<String> DYNAMIC = Set.of("", "worlds", "homes", "warps", "towns", "players", "admin");

    public MenuConfig(boolean sneakSwapEnabled, String defaultMenu, Map<String, MenuDefinition> menus) {
        this(sneakSwapEnabled, defaultMenu, menus, true);
    }

    public MenuConfig {
        menus = Map.copyOf(menus);
    }

    public static MenuConfig read(Reader input) throws IOException {
        try {
            JsonReader reader = new JsonReader(input);
            reader.setLenient(false);
            JsonObject root = object(readElement(reader, 0), "$", Set.of("version", "sneakSwapEnabled", "defaultMenu", "menus", "autoShortcuts"));
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("$", "存在多余 JSON 内容");
            if (integer(root, "version", "$", 1, 1) != 1) throw invalid("version", "只支持版本 1");
            boolean sneak = bool(root, "sneakSwapEnabled", false, "$");
            boolean autoShortcuts = bool(root, "autoShortcuts", true, "$");
            String defaultId = string(root, "defaultMenu", null, "$");
            menuId(defaultId, "defaultMenu");
            JsonObject definitions = object(required(root, "menus", "$"), "menus", null);
            if (definitions.size() == 0 || definitions.size() > 128) throw invalid("menus", "菜单数量必须为 1..128");
            Map<String, MenuDefinition> menus = new LinkedHashMap<>();
            for (var page : definitions.entrySet()) {
                String id = page.getKey();
                String field = "menus." + id;
                menuId(id, field);
                JsonObject obj = object(page.getValue(), field,
                        Set.of("title", "rows", "permission", "back", "dynamic", "buttons"));
                String title = text(obj, "title", null, field, 256);
                int rows = integer(obj, "rows", field, 1, 6);
                String permission = string(obj, "permission", "", field);
                permission(permission, field + ".permission");
                String back = string(obj, "back", "", field);
                if (!back.isEmpty()) menuId(back, field + ".back");
                String dynamic = string(obj, "dynamic", "", field);
                if (!DYNAMIC.contains(dynamic)) throw invalid(field + ".dynamic", "未知内置页面");
                if (!dynamic.isEmpty() && !dynamic.equals("admin") && rows != 6) {
                    throw invalid(field + ".rows", "动态列表必须使用 6 行（45 内容槽 + 页脚）");
                }
                JsonArray buttons = array(required(obj, "buttons", field), field + ".buttons", 54);
                if (!dynamic.isEmpty() && !dynamic.equals("admin") && !buttons.isEmpty()) {
                    throw invalid(field + ".buttons", "动态列表由数据生成，buttons 必须为空");
                }
                Set<Integer> slots = new HashSet<>();
                List<MenuDefinition.Entry> entries = new ArrayList<>();
                for (int i = 0; i < buttons.size(); i++) {
                    String at = field + ".buttons[" + i + "]";
                    JsonObject button = object(buttons.get(i), at, Set.of("slot", "icon", "name", "lore", "permission", "actions", "amount"));
                    int slot = integer(button, "slot", at, 0, rows * 9 - 1);
                    if (!slots.add(slot)) throw invalid(at + ".slot", "重复槽位 " + slot);
                    int amount = button.has("amount") ? integer(button, "amount", at, 1, 64) : 1;
                    String icon = string(button, "icon", null, at);
                    resourceId(icon, at + ".icon");
                    if (icon.equals("minecraft:air")) throw invalid(at + ".icon", "不能使用空气作为图标");
                    String name = text(button, "name", null, at, 256);
                    List<String> lore = new ArrayList<>();
                    JsonArray lines = button.has("lore") ? array(button.get("lore"), at + ".lore", 32) : new JsonArray();
                    for (int n = 0; n < lines.size(); n++) {
                        String line = primitiveString(lines.get(n), at + ".lore[" + n + "]");
                        validateText(line, at + ".lore[" + n + "]", 512);
                        lore.add(line);
                    }
                    String node = string(button, "permission", "", at);
                    permission(node, at + ".permission");
                    List<MenuAction> actions = new ArrayList<>();
                    JsonArray rawActions = array(required(button, "actions", at), at + ".actions", 16);
                    if (rawActions.isEmpty()) throw invalid(at + ".actions", "至少需要一个动作");
                    boolean navigated = false;
                    for (int n = 0; n < rawActions.size(); n++) {
                        String actionAt = at + ".actions[" + n + "]";
                        JsonObject action = object(rawActions.get(n), actionAt, Set.of("type", "value", "permission", "volume", "pitch"));
                        try {
                            MenuAction.Type type = MenuAction.Type.valueOf(string(action, "type", null, actionAt));
                            if (navigated) throw invalid(actionAt, "OPEN/BACK/CLOSE/命令/传送必须为最后一个动作");
                            String value = string(action, "value", "", actionAt);
                            if (value.length() > 2048) throw invalid(actionAt + ".value", "内容过长");
                            actions.add(new MenuAction(type, value, string(action, "permission", "", actionAt),
                                    number(action, "volume", 1, actionAt), number(action, "pitch", 1, actionAt)));
                            navigated = type != MenuAction.Type.MESSAGE && type != MenuAction.Type.SOUND;
                        } catch (IllegalArgumentException e) {
                            throw invalid(actionAt, e.getMessage());
                        }
                    }
                    entries.add(new MenuDefinition.Entry(slot, icon, name, lore, node, actions, amount));
                }
                menus.put(id, new MenuDefinition(id, title, rows, permission, back, dynamic, entries));
            }
            if (!menus.containsKey(defaultId)) throw invalid("defaultMenu", "菜单不存在: " + defaultId);
            for (MenuDefinition page : menus.values()) {
                if (!page.back().isEmpty() && !menus.containsKey(page.back())) throw invalid("menus." + page.id() + ".back", "返回页面不存在");
                for (var entry : page.buttons()) for (var action : entry.actions()) {
                    if (action.type() == MenuAction.Type.OPEN && !menus.containsKey(action.value())) {
                        throw invalid("menus." + page.id() + ".buttons[slot=" + entry.slot() + "]", "OPEN 目标不存在: " + action.value());
                    }
                }
            }
            // Back links are intentional reverse navigation, not OPEN edges. Each graph must itself be acyclic.
            detectCycles(menus, false);
            detectCycles(menus, true);
            return new MenuConfig(sneak, defaultId, menus, autoShortcuts);
        } catch (IllegalStateException | NumberFormatException e) {
            throw invalid("$", e.getMessage());
        }
    }

    /** Explicit schema serialization; never exposes mutable state from the configuration. */
    public static JsonObject toJson(MenuConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("sneakSwapEnabled", config.sneakSwapEnabled());
        root.addProperty("defaultMenu", config.defaultMenu());
        root.addProperty("autoShortcuts", config.autoShortcuts());
        JsonObject menus = new JsonObject();
        config.menus().keySet().stream().sorted().forEach(id -> {
            MenuDefinition definition = config.menus().get(id);
            JsonObject page = new JsonObject();
            page.addProperty("title", definition.title());
            page.addProperty("rows", definition.rows());
            page.addProperty("permission", definition.permission());
            page.addProperty("back", definition.back());
            page.addProperty("dynamic", definition.dynamic());
            JsonArray buttons = new JsonArray();
            for (MenuDefinition.Entry entry : definition.buttons()) {
                JsonObject button = new JsonObject();
                button.addProperty("slot", entry.slot());
                button.addProperty("icon", entry.icon());
                button.addProperty("name", entry.name());
                JsonArray lore = new JsonArray();
                entry.lore().forEach(lore::add);
                button.add("lore", lore);
                button.addProperty("permission", entry.permission());
                button.addProperty("amount", entry.amount());
                JsonArray actions = new JsonArray();
                for (MenuAction action : entry.actions()) {
                    JsonObject raw = new JsonObject();
                    raw.addProperty("type", action.type().name());
                    raw.addProperty("value", action.value());
                    raw.addProperty("permission", action.permission());
                    if (action.type() == MenuAction.Type.SOUND) {
                        raw.addProperty("volume", action.volume());
                        raw.addProperty("pitch", action.pitch());
                    }
                    actions.add(raw);
                }
                button.add("actions", actions);
                buttons.add(button);
            }
            page.add("buttons", buttons);
            menus.add(id, page);
        });
        root.add("menus", menus);
        return root;
    }

    private static void detectCycles(Map<String, MenuDefinition> menus, boolean back) {
        Map<String, Integer> states = new HashMap<>();
        for (String id : menus.keySet()) visit(id, menus, states, new ArrayList<>(), back);
    }

    private static void visit(String id, Map<String, MenuDefinition> menus, Map<String, Integer> states,
                              List<String> path, boolean back) {
        if (states.getOrDefault(id, 0) == 2) return;
        if (states.getOrDefault(id, 0) == 1) throw invalid("menus." + id, (back ? "back" : "OPEN") + " 循环引用: " + path + " -> " + id);
        states.put(id, 1);
        path.add(id);
        MenuDefinition page = menus.get(id);
        if (back) {
            if (!page.back().isEmpty()) visit(page.back(), menus, states, path, true);
        } else {
            for (var button : page.buttons()) for (var action : button.actions()) {
                if (action.type() == MenuAction.Type.OPEN) visit(action.value(), menus, states, path, false);
            }
        }
        path.removeLast();
        states.put(id, 2);
    }

    private static JsonElement readElement(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw invalid(reader.getPath(), "JSON 嵌套过深");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw invalid(reader.getPath(), "重复字段 " + name);
                    object.add(name, readElement(reader, depth + 1));
                    if (object.size() > 256) throw invalid(reader.getPath(), "对象字段过多");
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(readElement(reader, depth + 1));
                    if (array.size() > 256) throw invalid(reader.getPath(), "数组过长");
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            default -> throw invalid(reader.getPath(), "不允许 null 或无效 JSON 值");
        };
    }

    private static JsonObject object(JsonElement value, String at, Set<String> fields) {
        if (value == null || !value.isJsonObject()) throw invalid(at, "必须是对象");
        JsonObject object = value.getAsJsonObject();
        if (fields != null) for (String key : object.keySet()) if (!fields.contains(key)) throw invalid(at + "." + key, "未知字段");
        return object;
    }

    private static JsonArray array(JsonElement value, String at, int max) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() > max) throw invalid(at, "必须是最多 " + max + " 个元素的数组");
        return value.getAsJsonArray();
    }

    private static JsonElement required(JsonObject obj, String name, String at) {
        if (!obj.has(name)) throw invalid(at + "." + name, "缺少字段");
        return obj.get(name);
    }

    private static String primitiveString(JsonElement value, String at) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(at, "必须是字符串");
        return value.getAsString();
    }

    private static String string(JsonObject obj, String name, String fallback, String at) {
        return !obj.has(name) && fallback != null ? fallback : primitiveString(required(obj, name, at), at + "." + name);
    }

    private static String text(JsonObject obj, String name, String fallback, String at, int max) {
        String text = string(obj, name, fallback, at);
        validateText(text, at + "." + name, max);
        if (text.isBlank()) throw invalid(at + "." + name, "不能为空");
        return text;
    }

    private static void validateText(String text, String at, int max) {
        if (text.length() > max || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid(at, "文本过长或含控制字符");
        try { MenuAction.validatePlaceholders(text, MenuAction.TEXT_PLACEHOLDERS); }
        catch (IllegalArgumentException e) { throw invalid(at, e.getMessage()); }
    }

    private static int integer(JsonObject obj, String name, String at, int min, int max) {
        JsonElement value = required(obj, name, at);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(at + "." + name, "必须是整数");
        try {
            int number = value.getAsBigDecimal().intValueExact();
            if (number < min || number > max) throw invalid(at + "." + name, "范围必须为 " + min + ".." + max);
            return number;
        } catch (ArithmeticException e) { throw invalid(at + "." + name, "必须是整数"); }
    }

    private static boolean bool(JsonObject obj, String name, boolean fallback, String at) {
        if (!obj.has(name)) return fallback;
        JsonElement value = obj.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid(at + "." + name, "必须是布尔值");
        return value.getAsBoolean();
    }

    private static float number(JsonObject obj, String name, float fallback, String at) {
        if (!obj.has(name)) return fallback;
        JsonElement value = obj.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(at + "." + name, "必须是数字");
        return value.getAsFloat();
    }

    static void permission(String node, String at) {
        if (node == null || node.length() > 128 || !node.isEmpty() && !node.matches("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*")) throw invalid(at, "无效权限节点");
    }

    static void menuId(String id, String at) {
        if (id == null || !id.matches("[a-z0-9_-]{1,64}")) throw invalid(at, "菜单 ID 必须为 1..64 个小写字母/数字/_/-");
    }

    static void resourceId(String id, String at) {
        if (id == null || id.length() > 256 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid(at, "必须是 namespace:path 资源 ID");
    }

    private static IllegalArgumentException invalid(String at, String message) {
        return new IllegalArgumentException(at + ": " + message);
    }

    /** A candidate is fully parsed and registry-validated before either path or active config changes. */
    public static final class Store {
        private volatile MenuConfig current;
        private Path path;
        private String diskText;

        public MenuConfig current() { return current; }

        public synchronized void load(Path target, Consumer<MenuConfig> validate) throws IOException {
            Path absolute = target.toAbsolutePath().normalize();
            if (!Files.exists(absolute)) {
                if (current != null) throw new IOException(absolute + ": 菜单文件已被删除，保留有效配置");
                Path parent = absolute.getParent();
                if (parent != null) Files.createDirectories(parent);
                try (InputStream defaults = MenuConfig.class.getResourceAsStream("/defaults/menus.json")) {
                    if (defaults == null) throw new IOException("缺少内置 /defaults/menus.json");
                    Files.copy(defaults, absolute);
                }
            }
            if (Files.size(absolute) > 1_048_576) throw new IllegalArgumentException(absolute + ": 配置超过 1 MiB");
            String loaded = Files.readString(absolute, StandardCharsets.UTF_8);
            if (loaded.getBytes(StandardCharsets.UTF_8).length > 1_048_576) throw new IllegalArgumentException(absolute + ": 配置超过 1 MiB");
            MenuConfig candidate;
            try (Reader reader = new StringReader(loaded)) {
                candidate = read(reader);
                validate.accept(candidate);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(absolute + ": " + e.getMessage(), e);
            } catch (IOException e) {
                throw new IOException(absolute + ": " + e.getMessage(), e);
            }
            path = absolute;
            diskText = loaded;
            current = candidate;
        }

        /** Optimistic identity and exact-disk-text checks protect both runtime and manual edits. */
        public synchronized void save(MenuConfig expected, JsonObject candidate,
                                      Consumer<MenuConfig> registryValidator) throws IOException {
            if (path == null || current == null) throw new IOException("菜单尚未加载");
            if (expected != current) throw new IOException("菜单配置已更改，请重新打开编辑器");
            String text = candidate.toString();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 1_048_576) throw new IllegalArgumentException(path + ": 配置超过 1 MiB");
            MenuConfig parsed = read(new StringReader(text));
            registryValidator.accept(parsed);
            // A validator must not be able to invalidate the optimistic snapshot by reentering.
            if (expected != current) throw new IOException("菜单配置已更改，请重新打开编辑器");
            checkDisk();
            Path temporary = null;
            try {
                temporary = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
                Files.write(temporary, bytes);
                checkDisk();
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                diskText = text;
                current = parsed;
            } finally {
                if (temporary != null) Files.deleteIfExists(temporary);
            }
        }

        private void checkDisk() throws IOException {
            if (!Files.exists(path) || !diskText.equals(Files.readString(path, StandardCharsets.UTF_8))) {
                throw new IOException(path + ": 菜单文件已被外部修改或删除，请先重新加载");
            }
        }

        public synchronized void reload(Consumer<MenuConfig> validate) throws IOException {
            if (path == null) throw new IOException("菜单尚未加载");
            load(path, validate);
        }
    }
}
