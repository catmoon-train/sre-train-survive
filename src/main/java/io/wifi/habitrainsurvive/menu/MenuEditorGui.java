package io.wifi.habitrainsurvive.menu;

import com.google.gson.*;
import io.wifi.habitrainsurvive.util.MessageUtil;
import io.wifi.habitrainsurvive.world.WorldManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;

import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Per-administrator drafts. Display items are never inventory sources; previews never run actions. */
public final class MenuEditorGui {
    private static final Map<UUID, MenuDraft> SESSIONS = new HashMap<>();
    private static final Set<String> BUILT_INS = Set.of("main", "worlds", "homes", "warps", "towns", "players", "admin");
    private MenuEditorGui() { }
    public static void registerEvents() {
        MenuTextInput.registerEvents();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.remove(handler.getPlayer().getUUID()));
    }
    public static void clear() { SESSIONS.clear(); MenuTextInput.clear(); }
    private static boolean valid(ServerPlayer p, MenuDraft draft) { return p.hasPermissions(2) && SESSIONS.get(p.getUUID()) == draft; }
    private static MenuGui.Button button(MenuDraft draft, Item icon, String title, Consumer<ServerPlayer> action, String... lore) {
        return new MenuGui.Button(MenuGui.icon(icon, title, lore), p -> {
            if (!valid(p, draft)) { MessageUtil.sendError(p, "菜单编辑", "权限或草稿已失效，请使用 /menu edit 重新打开。"); p.closeContainer(); return; }
            try { action.accept(p); }
            catch (IllegalArgumentException e) { MessageUtil.sendError(p, "菜单编辑", e.getMessage()); }
        });
    }
    private static String text(JsonObject object, String field, String fallback) { return object.has(field) ? object.get(field).getAsString() : fallback; }
    private static int number(JsonObject object, String field, int fallback) { return object.has(field) ? object.get(field).getAsInt() : fallback; }
    private static String displayText(String value, int max) { return GuiInput.text(value, max).replace('&', '§'); }
    private static String permission(String value) {
        String node = value.trim().equals("-") ? "" : value.trim(); MenuConfig.permission(node, "permission"); return node;
    }
    private static String initial(String value) { return value.isEmpty() ? "-" : value; }
    private static Item material(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.ITEM.get(id) == Items.AIR)
            throw new IllegalArgumentException("图标物品不存在或为空气，请输入 namespace:item");
        return BuiltInRegistries.ITEM.get(id);
    }
    private static void prompt(ServerPlayer p, MenuDraft draft, String title, String value, Consumer<String> accept, Runnable back) {
        GuiInput.open(p, title, value, () -> valid(p, draft), accept, back);
    }
    private static void longPrompt(ServerPlayer p, MenuDraft draft, String value, int max, Consumer<String> accept, Runnable back) {
        MenuTextInput.request(p, value, max, () -> valid(p, draft), accept, back);
    }
    private static void confirm(ServerPlayer p, MenuDraft draft, String title, Consumer<ServerPlayer> accept, Consumer<ServerPlayer> back) {
        MenuGui.openSlots(p, title, 3, Map.of(11, button(draft, Items.RED_CONCRETE, "确认", accept),
                15, button(draft, Items.LIME_CONCRETE, "取消", back)));
    }
    public static void open(ServerPlayer player) { open(player, null); }
    public static void open(ServerPlayer player, String id) {
        if (!player.hasPermissions(2)) { MessageUtil.sendError(player, "菜单编辑", "需要原版 OP 2 权限"); return; }
        MenuConfig config = MenuManager.get().snapshot();
        if (config == null) { MessageUtil.sendError(player, "菜单编辑", "菜单尚未加载"); return; }
        MenuTextInput.cancel(player, false);
        MenuDraft draft = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new MenuDraft(config));
        if (id == null || id.isEmpty()) home(player, draft);
        else {
            try { editMenu(player, draft, id); }
            catch (IllegalArgumentException e) { MessageUtil.sendError(player, "菜单编辑", e.getMessage()); home(player, draft); }
        }
    }
    private static void home(ServerPlayer player, MenuDraft draft) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(draft, Items.EMERALD, "新建自定义菜单", p -> prompt(p, draft, "新菜单ID（字母数字）", "custom_menu", value -> {
            String id = draft.create(value.trim()); editMenu(p, draft, id);
        }, () -> home(p, draft)), "最多128个菜单；创建后编辑标题与按钮"));
        buttons.add(button(draft, Items.WRITABLE_BOOK, "保存全部草稿" + (draft.dirty() ? " · 有未保存修改" : " · 未修改"), p -> save(p, draft),
                "严格检查引用、动作与图标，成功才替换配置", "保存后立即生效并写入 menus.json"));
        buttons.add(button(draft, Items.REPEATER, "全局菜单设置", p -> settings(p, draft), "默认菜单、Shift+F快捷键、自动补齐入口"));
        buttons.add(button(draft, Items.BARRIER, "放弃草稿 / 刷新到当前配置", p -> confirm(p, draft, "确认放弃全部未保存修改？", sp -> {
            MenuDraft fresh = new MenuDraft(MenuManager.get().snapshot()); SESSIONS.put(sp.getUUID(), fresh); home(sp, fresh);
        }, sp -> home(sp, draft)), "同时放弃本草稿内所有菜单修改", "手动改过文件请先 /menu reload 再放弃旧草稿"));
        for (String id : draft.menuIds()) {
            JsonObject page = draft.page(id);
            buttons.add(button(draft, Items.FILLED_MAP, text(page, "title", id), p -> editMenu(p, draft, id),
                    "ID: " + id + " · " + number(page, "rows", 3) + "行 · " + page.getAsJsonArray("buttons").size() + "个按钮",
                    "类型: " + initial(text(page, "dynamic", "")), "点击编辑；所有更改先留在草稿"));
        }
        MenuGui.open(player, "菜单编辑器 · " + (draft.dirty() ? "未保存" : "已同步"), buttons, 0, p -> MenuManager.get().open(p, null));
    }
    private static void save(ServerPlayer player, MenuDraft draft) {
        if (!draft.dirty()) { MessageUtil.sendInfo(player, "菜单编辑", "没有未保存的修改。"); return; }
        try {
            MenuManager.get().saveDraft(player, draft);
            MenuDraft fresh = new MenuDraft(MenuManager.get().snapshot()); SESSIONS.put(player.getUUID(), fresh);
            MessageUtil.sendSuccess(player, "菜单编辑", "菜单已保存并立即生效。"); home(player, fresh);
        } catch (IOException | IllegalArgumentException e) {
            MessageUtil.sendError(player, "菜单编辑", "未保存，草稿和有效配置保留：" + e.getMessage());
        }
    }
    private static void settings(ServerPlayer player, MenuDraft draft) {
        JsonObject root = draft.json();
        MenuGui.openSlots(player, "全局菜单设置 · 草稿", 3, Map.of(
                10, button(draft, Items.COMPASS, "默认菜单: " + root.get("defaultMenu").getAsString(), p -> chooseMenu(p, draft, "选择默认菜单", null,
                        id -> { draft.setDefaultMenu(id); settings(p, draft); }, () -> settings(p, draft))),
                12, button(draft, Items.IRON_INGOT, "Shift+F: " + root.get("sneakSwapEnabled").getAsBoolean(), p -> {
                    draft.setSneakSwap(!draft.json().get("sneakSwapEnabled").getAsBoolean()); settings(p, draft);
                }),
                14, button(draft, Items.REPEATER, "自动补齐系统入口: " + root.get("autoShortcuts").getAsBoolean(), p -> {
                    draft.setAutoShortcuts(!draft.json().get("autoShortcuts").getAsBoolean()); settings(p, draft);
                }, "手动增删默认/管理页布局会关闭此项", "关闭后完全按配置布局，不再补回已删按钮"),
                18, button(draft, Items.ARROW, "返回", p -> home(p, draft))));
    }
    private static void editMenu(ServerPlayer player, MenuDraft draft, String id) {
        JsonObject page = draft.page(id);
        Runnable back = () -> editMenu(player, draft, id);
        Map<Integer, MenuGui.Button> buttons = new HashMap<>();
        buttons.put(4, button(draft, Items.FILLED_MAP, text(page, "title", id), p -> { }, "ID: " + id, "本页修改只进入草稿；到编辑器主页统一保存"));
        buttons.put(10, button(draft, Items.NAME_TAG, "修改标题", p -> prompt(p, draft, "菜单标题（支持&颜色）", text(page, "title", id), value -> {
            page.addProperty("title", displayText(value, 256)); editMenu(p, draft, id);
        }, back), text(page, "title", id)));
        buttons.put(11, button(draft, Items.PAPER, "行数: " + number(page, "rows", 3), p -> prompt(p, draft, "菜单行数（1至6）", String.valueOf(number(page, "rows", 3)), value -> {
            draft.resize(id, GuiInput.integer(value, 1, 6)); editMenu(p, draft, id);
        }, back), "缩小时不会自动丢弃超出范围的按钮"));
        buttons.put(12, button(draft, Items.ITEM_FRAME, "编辑按钮与布局", p -> layout(p, draft, id, 0), "点击空格新增按钮，点击已有按钮编辑", "布局页标明真实行/列，末行在第二页"));
        buttons.put(14, button(draft, Items.OAK_DOOR, "返回页: " + initial(text(page, "back", "")), p -> {
            List<MenuGui.Button> options = new ArrayList<>();
            options.add(button(draft, Items.BARRIER, "无返回页（BACK关闭菜单）", sp -> { page.addProperty("back", ""); editMenu(sp, draft, id); }));
            for (String target : draft.menuIds()) if (!target.equals(id)) options.add(button(draft, Items.FILLED_MAP, target, sp -> { page.addProperty("back", target); editMenu(sp, draft, id); }));
            MenuGui.open(p, "选择返回页", options, 0, sp -> editMenu(sp, draft, id));
        }, "子菜单建议使用BACK返回，避免OPEN双向循环"));
        buttons.put(15, button(draft, Items.TRIPWIRE_HOOK, "打开权限", p -> prompt(p, draft, "权限节点（-清空）", initial(text(page, "permission", "")), value -> {
            page.addProperty("permission", permission(value)); editMenu(p, draft, id);
        }, back), initial(text(page, "permission", "")), "留空表示普通玩家也可打开"));
        buttons.put(16, button(draft, Items.REPEATER, "菜单类型", p -> chooseType(p, draft, id), initial(text(page, "dynamic", "")), "普通静态菜单或系统自动生成列表"));
        buttons.put(19, button(draft, Items.ENDER_EYE, "安全预览", p -> preview(p, draft, id), "不执行指令、不传送、不收费", "点击已有按钮返回编辑；Esc退出后 /menu edit 恢复"));
        buttons.put(20, button(draft, Items.MAP, "复制整个菜单", p -> prompt(p, draft, "副本菜单ID", id.substring(0, Math.min(id.length(), 40)) + "_copy", value -> {
            editMenu(p, draft, draft.copy(id, value.trim()));
        }, back), "按钮、说明、动作独立复制"));
        buttons.put(21, button(draft, Items.BLACK_STAINED_GLASS_PANE, "填充装饰", p -> fill(p, draft, id), "边框或全部空槽；不覆盖已有按钮"));
        if (!BUILT_INS.contains(id)) {
            buttons.put(23, button(draft, Items.NAME_TAG, "重命名菜单ID", p -> prompt(p, draft, "新菜单ID", id, value -> editMenu(p, draft, draft.rename(id, value.trim())), back), "同步更新菜单跳转、返回页和默认菜单引用"));
            buttons.put(24, button(draft, Items.BARRIER, "删除自定义菜单", p -> confirm(p, draft, "确认删除菜单 " + id + "？", sp -> { draft.delete(id); home(sp, draft); }, sp -> editMenu(sp, draft, id)), "正在被引用或作为默认页时拒绝删除"));
        }
        buttons.put(27, button(draft, Items.ARROW, "返回编辑器主页", p -> home(p, draft)));
        buttons.put(31, button(draft, Items.WRITABLE_BOOK, "保存全部草稿", p -> save(p, draft)));
        buttons.put(35, button(draft, Items.BARRIER, "关闭（草稿保留至离线）", ServerPlayer::closeContainer));
        MenuGui.openSlots(player, "编辑菜单 · " + id, 4, buttons);
    }
    private static boolean generated(JsonObject page) { String type = text(page, "dynamic", ""); return !type.isEmpty() && !type.equals("admin"); }
    private static void chooseType(ServerPlayer player, MenuDraft draft, String id) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        String[] types = {"", "worlds", "homes", "warps", "towns", "players", "admin"};
        String[] names = {"静态自定义菜单", "世界列表", "玩家的家", "公共地标", "城镇列表", "在线交易玩家", "管理员导航（仍可编辑按钮）"};
        for (int i = 0; i < types.length; i++) {
            String type = types[i];
            buttons.add(button(draft, Items.REPEATER, names[i], p -> {
                Consumer<ServerPlayer> apply = sp -> {
                    JsonObject page = draft.page(id); page.addProperty("dynamic", type);
                    if (generated(page)) { page.addProperty("rows", 6); page.add("buttons", new JsonArray()); }
                    editMenu(sp, draft, id);
                };
                if (!type.isEmpty() && !type.equals("admin") && draft.page(id).getAsJsonArray("buttons").size() > 0)
                    confirm(p, draft, "切换会清空此菜单的手工按钮，确认？", apply, sp -> editMenu(sp, draft, id));
                else apply.accept(p);
            }, "自动列表内容由服务器生成；不可手工摆放按钮"));
        }
        MenuGui.open(player, "选择菜单类型", buttons, 0, p -> editMenu(p, draft, id));
    }
    private static void layout(ServerPlayer player, MenuDraft draft, String id, int pageIndex) {
        JsonObject page = draft.page(id);
        if (generated(page)) { MessageUtil.sendError(player, "菜单编辑", "本页内容由系统生成，只能编辑标题/权限/返回页；切换静态类型后可手工布局。"); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (int slot = 0; slot < number(page, "rows", 3) * 9; slot++) {
            int target = slot; JsonObject entry = draft.button(id, slot);
            buttons.add(button(draft, entry == null ? Items.LIGHT_GRAY_STAINED_GLASS_PANE : material(text(entry, "icon", "minecraft:paper")),
                    "槽位 " + (slot + 1) + " · 第" + (slot / 9 + 1) + "行第" + (slot % 9 + 1) + "列", p -> {
                        if (draft.button(id, target) == null) draft.addButton(id, target); editButton(p, draft, id, target);
                    }, entry == null ? "空格：点击新增按钮" : text(entry, "name", "按钮"), "配置JSON槽位从0开始，编辑器显示从1开始"));
        }
        MenuGui.open(player, "布局 · " + id, buttons, pageIndex, p -> editMenu(p, draft, id));
    }
    private static void fill(ServerPlayer player, MenuDraft draft, String id) {
        if (generated(draft.page(id))) throw new IllegalArgumentException("自动列表不能填充装饰");
        Consumer<Boolean> apply = border -> {
            int rows = number(draft.page(id), "rows", 3);
            String icon = player.getMainHandItem().isEmpty() ? "minecraft:black_stained_glass_pane" : BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString();
            for (int slot = 0; slot < rows * 9; slot++) {
                if (draft.button(id, slot) != null || border && slot / 9 != 0 && slot / 9 != rows - 1 && slot % 9 != 0 && slot % 9 != 8) continue;
                JsonObject entry = draft.addButton(id, slot); entry.addProperty("icon", icon); entry.addProperty("name", "§r ");
                JsonObject action = entry.getAsJsonArray("actions").get(0).getAsJsonObject(); action.addProperty("value", "");
            }
            editMenu(player, draft, id);
        };
        MenuGui.openSlots(player, "只填充空槽 · 主手图标或黑玻璃", 3, Map.of(
                11, button(draft, Items.BLACK_STAINED_GLASS_PANE, "填充边框", p -> apply.accept(true)),
                15, button(draft, Items.GRAY_STAINED_GLASS_PANE, "填满所有空槽", p -> apply.accept(false)),
                18, button(draft, Items.ARROW, "取消", p -> editMenu(p, draft, id))));
    }
    private static void editButton(ServerPlayer player, MenuDraft draft, String id, int slot) {
        JsonObject entry = draft.button(id, slot);
        if (entry == null) { layout(player, draft, id, slot / 45); return; }
        Runnable back = () -> editButton(player, draft, id, slot);
        Map<Integer, MenuGui.Button> buttons = new HashMap<>();
        buttons.put(4, button(draft, material(text(entry, "icon", "minecraft:paper")), text(entry, "name", "按钮"), p -> { }, "槽位 " + (slot + 1), "只有保存全部草稿后玩家才会看到修改"));
        buttons.put(10, button(draft, Items.NAME_TAG, "按钮名称", p -> prompt(p, draft, "按钮名称（&颜色/占位符）", text(entry, "name", "按钮"), value -> {
            entry.addProperty("name", displayText(value, 256)); back.run();
        }, back), text(entry, "name", "按钮")));
        buttons.put(11, button(draft, Items.ITEM_FRAME, "图标材质", p -> chooseIcon(p, draft, value -> { entry.addProperty("icon", value); back.run(); }, back), text(entry, "icon", "minecraft:paper"), "从背包选择或输入物品ID，不消耗物品"));
        buttons.put(12, button(draft, Items.PAPER, "图标数量: " + number(entry, "amount", 1), p -> prompt(p, draft, "显示数量（1至64）", String.valueOf(number(entry, "amount", 1)), value -> {
            entry.addProperty("amount", GuiInput.integer(value, 1, 64)); back.run();
        }, back)));
        buttons.put(14, button(draft, Items.WRITABLE_BOOK, "按钮说明 / Lore", p -> lore(p, draft, id, slot), "共" + entry.getAsJsonArray("lore").size() + "行，最多32行"));
        buttons.put(15, button(draft, Items.REPEATER, "点击动作", p -> actions(p, draft, id, slot), "共" + entry.getAsJsonArray("actions").size() + "项，最多16项"));
        buttons.put(16, button(draft, Items.TRIPWIRE_HOOK, "按钮权限", p -> prompt(p, draft, "权限节点（-清空）", initial(text(entry, "permission", "")), value -> {
            entry.addProperty("permission", permission(value)); back.run();
        }, back), initial(text(entry, "permission", ""))));
        buttons.put(20, button(draft, Items.PISTON, "移动到其它格子", p -> destination(p, draft, id, slot, false), "只能选空格，不覆盖已有按钮"));
        buttons.put(22, button(draft, Items.MAP, "复制到其它格子", p -> destination(p, draft, id, slot, true), "说明与动作独立复制"));
        buttons.put(24, button(draft, Items.BARRIER, "删除按钮", p -> confirm(p, draft, "确认从草稿删除此按钮？", sp -> {
            draft.removeButton(id, slot); layout(sp, draft, id, slot / 45);
        }, sp -> editButton(sp, draft, id, slot))));
        buttons.put(27, button(draft, Items.ARROW, "返回布局", p -> layout(p, draft, id, slot / 45)));
        buttons.put(31, button(draft, Items.ENDER_EYE, "安全预览菜单", p -> preview(p, draft, id)));
        MenuGui.openSlots(player, "编辑按钮 · 槽位 " + (slot + 1), 4, buttons);
    }
    private static void chooseIcon(ServerPlayer player, MenuDraft draft, Consumer<String> selected, Runnable back) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(draft, Items.WRITABLE_BOOK, "输入图标物品ID", p -> prompt(p, draft, "物品ID", "minecraft:paper", value -> {
            Item item = material(value.trim()); selected.accept(BuiltInRegistries.ITEM.getKey(item).toString());
        }, back)));
        Set<Item> seen = new HashSet<>();
        for (ItemStack stack : player.getInventory().items) if (!stack.isEmpty() && seen.add(stack.getItem())) {
            String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            buttons.add(button(draft, stack.getItem(), stack.getHoverName().getString(), p -> selected.accept(item), item, "只复制图标材质，不移动物品"));
        }
        for (Item item : List.of(Items.PAPER, Items.CHEST, Items.COMPASS, Items.EMERALD, Items.BARRIER, Items.BLACK_STAINED_GLASS_PANE)) if (seen.add(item)) {
            String value = BuiltInRegistries.ITEM.getKey(item).toString(); buttons.add(button(draft, item, value, p -> selected.accept(value)));
        }
        MenuGui.open(player, "选择图标 · 不消耗背包物品", buttons, 0, p -> back.run());
    }
    private static void destination(ServerPlayer player, MenuDraft draft, String id, int source, boolean copy) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (int slot = 0; slot < number(draft.page(id), "rows", 3) * 9; slot++) {
            int target = slot; if (draft.button(id, slot) != null) continue;
            buttons.add(button(draft, Items.LIME_STAINED_GLASS_PANE, "槽位" + (slot + 1) + "（" + (slot / 9 + 1) + "行" + (slot % 9 + 1) + "列）", p -> {
                draft.moveButton(id, source, target, copy); editButton(p, draft, id, target);
            }));
        }
        MenuGui.open(player, copy ? "选择复制目标空格" : "选择移动目标空格", buttons, 0, p -> editButton(p, draft, id, source));
    }
    private static void lore(ServerPlayer player, MenuDraft draft, String id, int slot) {
        JsonArray lines = draft.button(id, slot).getAsJsonArray("lore");
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(draft, Items.EMERALD, "新增说明行", p -> {
            if (lines.size() >= 32) throw new IllegalArgumentException("最多32行说明");
            lines.add("新说明"); loreLine(p, draft, id, slot, lines.size() - 1);
        }));
        for (int i = 0; i < lines.size(); i++) { int index = i; buttons.add(button(draft, Items.PAPER, "第" + (i + 1) + "行", p -> loreLine(p, draft, id, slot, index), lines.get(i).getAsString())); }
        MenuGui.open(player, "按钮说明 · 支持颜色与文本占位符", buttons, 0, p -> editButton(p, draft, id, slot));
    }
    private static void loreLine(ServerPlayer player, MenuDraft draft, String id, int slot, int index) {
        JsonArray lines = draft.button(id, slot).getAsJsonArray("lore"); if (index >= lines.size()) { lore(player, draft, id, slot); return; }
        Runnable back = () -> loreLine(player, draft, id, slot, index);
        Consumer<String> accepted = value -> { lines.set(index, new JsonPrimitive(displayText(value, 512))); back.run(); };
        Map<Integer, MenuGui.Button> buttons = new HashMap<>();
        buttons.put(10, button(draft, Items.NAME_TAG, "铁砧编辑此行", p -> prompt(p, draft, "说明（&颜色/占位符）", lines.get(index).getAsString(), accepted, back), "原版铁砧输入长度有限；长说明用聊天输入"));
        buttons.put(12, button(draft, Items.WRITABLE_BOOK, "长文本私密聊天输入", p -> longPrompt(p, draft, lines.get(index).getAsString(), 512, accepted, back), "可多条连续追加，不公开到频道"));
        if (index > 0) buttons.put(14, button(draft, Items.ARROW, "上移此行", p -> { swap(lines, index, index - 1); loreLine(p, draft, id, slot, index - 1); }));
        if (index + 1 < lines.size()) buttons.put(15, button(draft, Items.ARROW, "下移此行", p -> { swap(lines, index, index + 1); loreLine(p, draft, id, slot, index + 1); }));
        buttons.put(16, button(draft, Items.BARRIER, "删除此行", p -> { lines.remove(index); lore(p, draft, id, slot); }));
        buttons.put(18, button(draft, Items.ARROW, "返回说明列表", p -> lore(p, draft, id, slot)));
        MenuGui.openSlots(player, "编辑说明 · 第" + (index + 1) + "行", 3, buttons);
    }
    private static void swap(JsonArray array, int a, int b) { JsonElement old = array.get(a); array.set(a, array.get(b)); array.set(b, old); }
    private static void insert(JsonArray array, int at, JsonElement value) { array.add(value); for (int i = array.size() - 1; i > at; i--) array.set(i, array.get(i - 1)); array.set(at, value); }
    private static JsonObject action(MenuAction.Type type, String value) { JsonObject object = new JsonObject(); object.addProperty("type", type.name()); object.addProperty("value", value); return object; }
    private static MenuAction.Type type(JsonObject action) { return MenuAction.Type.valueOf(text(action, "type", "MESSAGE")); }
    private static String typeName(MenuAction.Type type) {
        return switch (type) {
            case OPEN -> "打开其它菜单"; case BACK -> "返回上一级"; case CLOSE -> "关闭菜单";
            case PLAYER_COMMAND -> "玩家身份执行指令"; case CONSOLE_COMMAND -> "控制台执行指令（OP4）";
            case MESSAGE -> "发送私人消息"; case SOUND -> "播放音效"; case TELEPORT -> "前往世界出生点"; case RTP -> "世界随机传送";
        };
    }
    private static void actions(ServerPlayer player, MenuDraft draft, String id, int slot) {
        JsonArray actions = draft.button(id, slot).getAsJsonArray("actions");
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(draft, Items.EMERALD, "新增动作", p -> {
            if (actions.size() >= 16) throw new IllegalArgumentException("每个按钮最多16个动作");
            int at = actions.size();
            if (at > 0 && type(actions.get(at - 1).getAsJsonObject()) != MenuAction.Type.MESSAGE && type(actions.get(at - 1).getAsJsonObject()) != MenuAction.Type.SOUND) at--;
            insert(actions, at, action(MenuAction.Type.MESSAGE, "请编辑按钮动作")); editAction(p, draft, id, slot, at);
        }, "消息/音效在前；跳转、指令、传送、关闭只能放最后"));
        for (int i = 0; i < actions.size(); i++) {
            int index = i; JsonObject object = actions.get(i).getAsJsonObject();
            buttons.add(button(draft, Items.REPEATER, (i + 1) + ". " + typeName(type(object)), p -> editAction(p, draft, id, slot, index),
                    text(object, "value", ""), "权限: " + initial(text(object, "permission", ""))));
        }
        MenuGui.open(player, "按钮动作 · 按顺序执行", buttons, 0, p -> editButton(p, draft, id, slot));
    }
    private static void editAction(ServerPlayer player, MenuDraft draft, String id, int slot, int index) {
        JsonArray actions = draft.button(id, slot).getAsJsonArray("actions");
        if (index >= actions.size()) { actions(player, draft, id, slot); return; }
        JsonObject object = actions.get(index).getAsJsonObject(); MenuAction.Type type = type(object);
        Runnable back = () -> editAction(player, draft, id, slot, index);
        Map<Integer, MenuGui.Button> buttons = new HashMap<>();
        buttons.put(4, button(draft, Items.REPEATER, "动作" + (index + 1) + " · " + typeName(type), p -> { }, text(object, "value", "")));
        buttons.put(10, button(draft, Items.COMPARATOR, "选择动作类型", p -> actionTypes(p, draft, id, slot, index)));
        if (type != MenuAction.Type.BACK && type != MenuAction.Type.CLOSE) {
            buttons.put(11, button(draft, Items.NAME_TAG, "编辑目标 / 内容", p -> actionValue(p, draft, id, slot, index, false), text(object, "value", "")));
            if (type == MenuAction.Type.MESSAGE || type == MenuAction.Type.PLAYER_COMMAND || type == MenuAction.Type.CONSOLE_COMMAND)
                buttons.put(12, button(draft, Items.WRITABLE_BOOK, "长文本私密聊天输入", p -> actionValue(p, draft, id, slot, index, true), "消息支持文本占位符；指令只支持{player}/{uuid}"));
        }
        buttons.put(14, button(draft, Items.TRIPWIRE_HOOK, "动作权限", p -> prompt(p, draft, "权限节点（-清空）", initial(text(object, "permission", "")), value -> {
            checkConsole(p, object); object.addProperty("permission", permission(value)); back.run();
        }, back), initial(text(object, "permission", ""))));
        if (type == MenuAction.Type.SOUND) {
            buttons.put(15, button(draft, Items.NOTE_BLOCK, "音量: " + text(object, "volume", "1"), p -> prompt(p, draft, "音量（0至4）", text(object, "volume", "1"), value -> {
                object.addProperty("volume", (float) GuiInput.number(value, 0, 4)); back.run();
            }, back)));
            buttons.put(16, button(draft, Items.JUKEBOX, "音调: " + text(object, "pitch", "1"), p -> prompt(p, draft, "音调（0.5至2）", text(object, "pitch", "1"), value -> {
                object.addProperty("pitch", (float) GuiInput.number(value, 0.5, 2)); back.run();
            }, back)));
        }
        if (index > 0) buttons.put(20, button(draft, Items.ARROW, "提前执行", p -> { swap(actions, index, index - 1); editAction(p, draft, id, slot, index - 1); }));
        if (index + 1 < actions.size()) buttons.put(22, button(draft, Items.ARROW, "延后执行", p -> { swap(actions, index, index + 1); editAction(p, draft, id, slot, index + 1); }));
        buttons.put(24, button(draft, Items.BARRIER, "删除此动作", p -> {
            if (actions.size() == 1) throw new IllegalArgumentException("按钮至少保留一个动作；装饰按钮请选【仅展示】");
            actions.remove(index); actions(p, draft, id, slot);
        }));
        buttons.put(27, button(draft, Items.ARROW, "返回动作列表", p -> actions(p, draft, id, slot)));
        MenuGui.openSlots(player, "编辑点击动作", 4, buttons);
    }
    private static void checkConsole(ServerPlayer player, JsonObject object) {
        if (type(object) == MenuAction.Type.CONSOLE_COMMAND && !player.hasPermissions(4)) throw new IllegalArgumentException("控制台指令编辑需要 OP 4");
    }
    private static void actionTypes(ServerPlayer player, MenuDraft draft, String id, int slot, int index) {
        JsonArray actions = draft.button(id, slot).getAsJsonArray("actions");
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(draft, Items.GRAY_STAINED_GLASS_PANE, "仅展示（装饰按钮）", p -> { actions.set(index, action(MenuAction.Type.MESSAGE, "")); editAction(p, draft, id, slot, index); }));
        for (MenuAction.Type type : MenuAction.Type.values()) {
            if (type == MenuAction.Type.CONSOLE_COMMAND && !player.hasPermissions(4)) continue;
            buttons.add(button(draft, Items.REPEATER, typeName(type), p -> {
                if (type == MenuAction.Type.CONSOLE_COMMAND && !p.hasPermissions(4)) throw new IllegalArgumentException("需要 OP 4");
                String value = switch (type) {
                    case OPEN -> draft.json().get("defaultMenu").getAsString(); case PLAYER_COMMAND -> "menu";
                    case CONSOLE_COMMAND -> "say 自定义菜单"; case MESSAGE -> "自定义菜单消息";
                    case SOUND -> "minecraft:block.note_block.pling"; case TELEPORT, RTP -> "minecraft:overworld"; case BACK, CLOSE -> "";
                };
                actions.set(index, action(type, value)); editAction(p, draft, id, slot, index);
            }));
        }
        MenuGui.open(player, "选择动作类型", buttons, 0, p -> editAction(p, draft, id, slot, index));
    }
    private static void actionValue(ServerPlayer player, MenuDraft draft, String id, int slot, int index, boolean chat) {
        JsonObject object = draft.button(id, slot).getAsJsonArray("actions").get(index).getAsJsonObject();
        checkConsole(player, object); MenuAction.Type type = type(object);
        Runnable back = () -> editAction(player, draft, id, slot, index);
        Consumer<String> accepted = raw -> {
            checkConsole(player, object);
            String value = raw.trim();
            if (type == MenuAction.Type.MESSAGE) value = value.equals("-") ? "" : value.replace('&', '§');
            if ((type == MenuAction.Type.PLAYER_COMMAND || type == MenuAction.Type.CONSOLE_COMMAND) && value.startsWith("/")) value = value.substring(1);
            new MenuAction(type, value, text(object, "permission", ""), 1, 1);
            if (type == MenuAction.Type.SOUND && !BuiltInRegistries.SOUND_EVENT.containsKey(ResourceLocation.parse(value))) throw new IllegalArgumentException("音效不存在");
            object.addProperty("value", value); back.run();
        };
        if (type == MenuAction.Type.OPEN) { chooseMenu(player, draft, "打开哪个菜单？", id, accepted, back); return; }
        if (type == MenuAction.Type.TELEPORT || type == MenuAction.Type.RTP) {
            List<MenuGui.Button> buttons = new ArrayList<>();
            WorldManager.get().all().forEach(world -> buttons.add(button(draft, Items.GRASS_BLOCK, world.name, p -> accepted.accept(world.id), world.id)));
            MenuGui.open(player, "选择目标世界", buttons, 0, p -> back.run()); return;
        }
        if (chat) longPrompt(player, draft, text(object, "value", ""), 2048, accepted, back);
        else prompt(player, draft, "动作值（消息-清空；指令可省/）", initial(text(object, "value", "")), accepted, back);
    }
    private static void chooseMenu(ServerPlayer player, MenuDraft draft, String title, String exclude, Consumer<String> selected, Runnable back) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (String id : draft.menuIds()) if (!id.equals(exclude)) buttons.add(button(draft, Items.FILLED_MAP, text(draft.page(id), "title", id), p -> selected.accept(id), id));
        MenuGui.open(player, title, buttons, 0, p -> back.run());
    }
    private static void preview(ServerPlayer player, MenuDraft draft, String id) {
        JsonObject page = draft.page(id);
        if (generated(page)) { MessageUtil.sendError(player, "菜单编辑", "动态列表内容由系统生成；保存后通过 /menu open " + id + " 查看。"); return; }
        Map<Integer, MenuGui.Button> buttons = new HashMap<>();
        for (JsonElement raw : page.getAsJsonArray("buttons")) {
            JsonObject entry = raw.getAsJsonObject(); int slot = number(entry, "slot", 0);
            String[] lore = new String[entry.getAsJsonArray("lore").size()];
            for (int i = 0; i < lore.length; i++) lore[i] = MenuManager.get().expandFor(player, entry.getAsJsonArray("lore").get(i).getAsString());
            ItemStack icon = MenuGui.icon(material(text(entry, "icon", "minecraft:paper")), MenuManager.get().expandFor(player, text(entry, "name", "按钮")), lore);
            icon.setCount(number(entry, "amount", 1));
            buttons.put(slot, new MenuGui.Button(icon, p -> {
                if (valid(p, draft)) { MessageUtil.sendInfo(p, "菜单预览", "预览不会执行按钮动作，已返回按钮编辑。"); editButton(p, draft, id, slot); }
            }));
        }
        MenuGui.openSlots(player, "预览（不执行）· " + MenuManager.get().expandFor(player, text(page, "title", id)), number(page, "rows", 3), buttons);
    }
}
