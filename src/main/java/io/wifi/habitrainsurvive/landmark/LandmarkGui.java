package io.wifi.habitrainsurvive.landmark;

import com.mojang.brigadier.CommandDispatcher;
import io.wifi.habitrainsurvive.cmi.WarpPoint;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.*;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.commands.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.function.Consumer;

public final class LandmarkGui {
    private LandmarkGui() { }
    private static MenuGui.Button button(Item icon, String title, Consumer<ServerPlayer> action, String... lore) {
        return new MenuGui.Button(MenuGui.icon(icon, title, lore), p -> {
            try { action.accept(p); } catch (IllegalArgumentException e) { MessageUtil.sendError(p, "地标", e.getMessage()); }
        });
    }
    private static Item icon(WarpPoint point) {
        ResourceLocation id = point.icon == null ? null : ResourceLocation.tryParse(point.icon);
        return id != null && BuiltInRegistries.ITEM.containsKey(id) && !BuiltInRegistries.ITEM.get(id).equals(Items.AIR)
                ? BuiltInRegistries.ITEM.get(id) : Items.ENDER_PEARL;
    }
    public static void open(ServerPlayer player) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        LandmarkManager.all().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            WarpPoint point = entry.getValue(); if (point == null || !point.enabled) return;
            buttons.add(button(icon(point), point.name, p -> LandmarkManager.teleport(p, entry.getKey()),
                    point.description == null ? "" : point.description,
                    point.pos == null ? "位置未设置" : point.pos.toShortString(),
                    "传送费用: " + EconomyManager.get().format(point.cost), "点击传送；移动或受伤会打断"));
        });
        if (LandmarkManager.admin(player)) buttons.add(button(Items.COMMAND_BLOCK, "编辑地标", LandmarkGui::openAdmin));
        MenuGui.open(player, "公共地标 · 选择目的地", buttons, 0, p -> MenuManager.get().open(p, null));
    }
    public static void openAdmin(ServerPlayer player) {
        if (!LandmarkManager.admin(player)) { MessageUtil.sendError(player, "地标", "需要地标管理权限"); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.EMERALD, "以当前位置新增地标", p -> GuiInput.open(p, "新地标ID（字母数字）", "new_landmark", () -> LandmarkManager.admin(p), value -> {
            String id = LandmarkManager.id(value);
            if (LandmarkManager.all().containsKey(id)) throw new IllegalArgumentException("这个ID已存在");
            WarpPoint point = new WarpPoint(id, GlobalPosInfo.fromPlayer(p), 0, p.getGameProfile().getName());
            LandmarkManager.all().put(id, point); ServerDataManager.saveAll(); edit(p, id, point);
        }, () -> openAdmin(p)), "创建后可修改中文名称、费用、图标与描述"));
        LandmarkManager.all().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            WarpPoint point = entry.getValue(); if (point == null) return;
            buttons.add(button(icon(point), point.name, p -> edit(p, entry.getKey(), point),
                    "ID: " + entry.getKey(), "状态: " + (point.enabled ? "开放" : "关闭"), "费用: " + EconomyManager.get().format(point.cost)));
        });
        MenuGui.open(player, "地标管理", buttons, 0, LandmarkGui::open);
    }
    private static void edit(ServerPlayer player, String key, WarpPoint point) {
        if (!LandmarkManager.editable(player, key, point)) { openAdmin(player); return; }
        Runnable back = () -> edit(player, key, LandmarkManager.all().get(key));
        Map<Integer, MenuGui.Button> buttons = new HashMap<>();
        buttons.put(10, button(Items.NAME_TAG, "显示名称", p -> GuiInput.open(p, "地标名称", point.name,
                () -> LandmarkManager.editable(p, key, point), value -> { LandmarkManager.update(p, key, point, n -> n.name = GuiInput.text(value, 64)); back.run(); }, back), point.name));
        buttons.put(11, button(Items.GOLD_INGOT, "传送费用", p -> GuiInput.open(p, "传送费用（0为免费）", String.valueOf(point.cost),
                () -> LandmarkManager.editable(p, key, point), value -> { LandmarkManager.update(p, key, point, n -> n.cost = Math.round(GuiInput.number(value, 0, 1e12) * 100) / 100.0); back.run(); }, back), EconomyManager.get().format(point.cost)));
        buttons.put(12, button(Items.WRITABLE_BOOK, "地标说明", p -> GuiInput.open(p, "地标说明", point.description == null || point.description.isBlank() ? "无说明" : point.description,
                () -> LandmarkManager.editable(p, key, point), value -> { LandmarkManager.update(p, key, point, n -> n.description = GuiInput.text(value, 160)); back.run(); }, back), point.description == null ? "" : point.description));
        buttons.put(14, button(icon(point), "使用主手物品作为图标", p -> {
            if (p.getMainHandItem().isEmpty()) throw new IllegalArgumentException("请先在主手拿上图标物品");
            LandmarkManager.update(p, key, point, n -> n.icon = BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString()); back.run();
        }, "不消耗物品"));
        buttons.put(15, button(Items.COMPASS, "更新为当前位置", p -> {
            LandmarkManager.update(p, key, point, n -> n.pos = GlobalPosInfo.fromPlayer(p)); back.run();
        }, point.pos == null ? "位置未设置" : point.pos.toShortString()));
        buttons.put(16, button(point.enabled ? Items.LIME_DYE : Items.RED_DYE, point.enabled ? "当前开放（点击关闭）" : "当前关闭（点击开放）", p -> {
            LandmarkManager.update(p, key, point, n -> n.enabled = !point.enabled); back.run();
        }));
        buttons.put(18, button(Items.ARROW, "返回", LandmarkGui::openAdmin));
        buttons.put(22, button(Items.BARRIER, "删除地标", p -> MenuGui.openSlots(p, "确认删除地标 " + point.name, 3, Map.of(
                11, button(Items.RED_CONCRETE, "确认删除", sp -> {
                    if (!LandmarkManager.editable(sp, key, point)) throw new IllegalArgumentException("地标已更新或没有权限");
                    LandmarkManager.all().remove(key); ServerDataManager.saveAll(); openAdmin(sp);
                }), 15, button(Items.LIME_CONCRETE, "取消", sp -> back.run())))));
        MenuGui.openSlots(player, "编辑地标 · " + key, 3, buttons);
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = dispatcher.register(Commands.literal("landmark").executes(ctx -> { open(ctx.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("admin").requires(s -> PermissionManager.get().hasPermission(s, "habi.landmark.admin", 2))
                        .executes(ctx -> { openAdmin(ctx.getSource().getPlayerOrException()); return 1; })));
        dispatcher.register(Commands.literal("landmarks").executes(ctx -> { open(ctx.getSource().getPlayerOrException()); return 1; }).redirect(root));
    }
}
