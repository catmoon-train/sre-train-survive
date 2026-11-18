package io.wifi.habitrainsurvive.plot;

import io.wifi.habitrainsurvive.claim.*;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.*;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import io.wifi.habitrainsurvive.world.WorldManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.function.Consumer;

public final class PlotGui {
    private PlotGui() { }
    private static PlotManager manager() { return PlotManager.get(); }
    public static boolean admin(ServerPlayer player) { return PermissionManager.get().hasPermission(player, "habi.plot.admin"); }
    private static boolean manage(ServerPlayer player, Claim claim) { return manager().exists(claim) && (claim.ownerUuid.equals(player.getUUID()) || admin(player)); }
    private static MenuGui.Button button(Item icon, String name, Consumer<ServerPlayer> action, String... lore) {
        return new MenuGui.Button(MenuGui.icon(icon, name, lore), p -> {
            try { action.accept(p); } catch (IllegalArgumentException e) { MessageUtil.sendError(p, "地皮", e.getMessage()); }
        });
    }
    public static void open(ServerPlayer player) {
        Map<Integer, MenuGui.Button> slots = new HashMap<>();
        double price = manager().price(player.getUUID());
        slots.put(10, button(Items.GRASS_BLOCK, "购买下一块地皮", p -> confirmBuy(p, manager().nextAvailable()),
                "64×64 可建区域 · 7格公共道路", "本次费用: " + EconomyManager.get().format(price), "每人首块免费，后续默认每块10000"));
        slots.put(12, button(Items.FILLED_MAP, "购买脚下地皮", p -> {
            if (!PlotGrid.WORLD.equals(p.serverLevel().dimension().location().toString())) throw new IllegalArgumentException("请先前往地皮世界");
            confirmBuy(p, PlotGrid.at(p.blockPosition().getX(), p.blockPosition().getZ()));
        }, "选择未出售的地皮，需二次确认", "站在道路上不可购买"));
        slots.put(14, button(Items.OAK_DOOR, "我的地皮与共享地皮", p -> list(p, false),
                "已拥有: " + manager().owned(player.getUUID()).size(), "成员授权、访客设置、传送与改名"));
        slots.put(16, button(Items.COMPASS, "前往地皮世界", p -> { p.closeContainer(); WorldManager.get().teleport(p, PlotGrid.WORLD); }, "独立超平坦世界；道路受保护"));
        if (admin(player)) slots.put(22, button(Items.COMMAND_BLOCK, "地皮管理", PlotGui::openAdmin));
        slots.put(18, button(Items.ARROW, "返回主菜单", p -> MenuManager.get().open(p, null)));
        slots.put(26, button(Items.BARRIER, "关闭", ServerPlayer::closeContainer));
        MenuGui.openSlots(player, "地皮大厅 · 首块免费", 3, slots);
    }
    public static void confirmBuy(ServerPlayer player, PlotGrid.Cell cell) {
        if (cell == null) throw new IllegalArgumentException("没有可分配地皮，或当前位置是公共道路");
        if (manager().get(cell) != null) throw new IllegalArgumentException("这块地皮已有人拥有");
        double price = manager().price(player.getUUID());
        MenuGui.openSlots(player, "确认购买地皮 " + cell.id(), 3, Map.of(
                11, button(Items.LIME_CONCRETE, "确认购买", p -> {
                    Claim bought = manager().purchase(p, cell, price); openPlot(p, bought);
                }, "费用: " + EconomyManager.get().format(price), "X " + cell.minX() + "~" + cell.maxX() + " / Z " + cell.minZ() + "~" + cell.maxZ(), "确认后获得整块64×64地皮；无自动退款"),
                15, button(Items.RED_CONCRETE, "取消，不扣费", PlotGui::open)));
    }
    public static void list(ServerPlayer player, boolean all) {
        if (all && !admin(player)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (Claim claim : (all ? manager().all() : manager().accessible(player.getUUID()))) {
            buttons.add(button(Items.GRASS_BLOCK, claim.name, p -> openPlot(p, claim),
                    "主人: " + claim.ownerName, "64×64 · 成员 " + claim.members.size(), "点击管理或访问"));
        }
        MenuGui.open(player, all ? "全部地皮" : "我的与共享地皮", buttons, 0, PlotGui::open);
    }
    public static void openPlot(ServerPlayer player, Claim claim) {
        if (!manager().exists(claim)) { open(player); return; }
        Map<Integer, MenuGui.Button> slots = new HashMap<>();
        slots.put(4, button(Items.GRASS_BLOCK, claim.name, p -> { }, "主人: " + claim.ownerName,
                "X " + claim.minX + "~" + claim.maxX + " / Z " + claim.minZ + "~" + claim.maxZ, "64×64 · 独立地皮保护"));
        slots.put(10, button(Items.ENDER_PEARL, "传送至地皮", p -> manager().teleport(p, claim), "遵守战斗、监禁与传送读条限制"));
        if (manage(player, claim)) {
            slots.put(12, button(Items.NAME_TAG, "修改地皮名称", p -> GuiInput.open(p, "地皮名称", claim.name,
                    () -> manage(p, claim), value -> {
                        String name = GuiInput.text(value, 40); manager().update(claim, c -> c.name = name); refresh(p, claim);
                    }, () -> refresh(p, claim)), claim.name));
            slots.put(14, button(Items.PLAYER_HEAD, "地皮成员", p -> members(p, claim), "成员可建造并使用容器；仅主人可管理成员"));
            slots.put(16, button(Items.REPEATER, "访客与保护设置", p -> flags(p, claim), "控制访客权限；世界全局限制仍优先"));
            slots.put(22, button(Items.COMPASS, "设置传送点为当前位置", p -> {
                require(p, claim);
                if (!claim.contains(PlotGrid.WORLD.equals(p.serverLevel().dimension().location().toString()) ? PlotGrid.WORLD : "", p.blockPosition().getX(), p.blockPosition().getY(), p.blockPosition().getZ()))
                    throw new IllegalArgumentException("请站在这块地皮内部设置传送点");
                manager().update(claim, c -> c.teleportPos = GlobalPosInfo.fromPlayer(p)); refresh(p, claim);
            }, "初始传送点位于门前道路，不会被建房覆盖"));
        }
        slots.put(18, button(Items.ARROW, "返回地皮大厅", PlotGui::open));
        slots.put(26, button(Items.BARRIER, "关闭", ServerPlayer::closeContainer));
        MenuGui.openSlots(player, "地皮 · " + claim.name, 3, slots);
    }
    private static void require(ServerPlayer player, Claim claim) { if (!manage(player, claim)) throw new IllegalArgumentException("没有管理权限或地皮已更新，请刷新"); }
    private static void refresh(ServerPlayer player, Claim old) { Claim next = manager().byId(old.id); if (next == null) open(player); else openPlot(player, next); }
    private static void flags(ServerPlayer player, Claim claim) {
        require(player, claim);
        List<MenuGui.Button> buttons = new ArrayList<>();
        // World-level safety (fire, explosions, fluids, pistons and PvP) is not overridable by plot flags.
        for (ClaimFlag flag : List.of(ClaimFlag.BLOCK_BREAK, ClaimFlag.BLOCK_PLACE, ClaimFlag.INTERACT, ClaimFlag.CONTAINER,
                ClaimFlag.WORKSTATION, ClaimFlag.MOB_DAMAGE, ClaimFlag.CROP_TRAMPLE, ClaimFlag.ITEM_PICKUP, ClaimFlag.ENDER_PEARL, ClaimFlag.ENTER, ClaimFlag.TELEPORT)) {
            buttons.add(button(claim.getFlag(flag) ? Items.LIME_DYE : Items.RED_DYE, flag.getTitle() + ": " + (claim.getFlag(flag) ? "允许" : "禁止"), p -> {
                require(p, claim); manager().update(claim, c -> c.flags.put(flag, !c.getFlag(flag)));
                flags(p, manager().byId(claim.id));
            }, flag.getDescription(), "针对非成员的访问权限；点击切换"));
        }
        MenuGui.open(player, "地皮访客权限", buttons, 0, p -> refresh(p, claim));
    }
    private static void members(ServerPlayer player, Claim claim) {
        require(player, claim);
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.EMERALD, "添加在线玩家", p -> addMember(p, claim), "授予建造与容器权限，不授予成员管理权"));
        claim.members.forEach((uuid, role) -> buttons.add(button(Items.PLAYER_HEAD, claim.memberNames.getOrDefault(uuid, uuid.toString()), p -> {
            require(p, claim); manager().update(claim, c -> { c.members.remove(uuid); c.memberNames.remove(uuid); });
            members(p, manager().byId(claim.id));
        }, "权限: " + role, "点击移除授权")));
        MenuGui.open(player, "地皮成员", buttons, 0, p -> refresh(p, claim));
    }
    private static void addMember(ServerPlayer player, Claim claim) {
        require(player, claim);
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            if (target.getUUID().equals(claim.ownerUuid) || claim.members.containsKey(target.getUUID())) continue;
            UUID uuid = target.getUUID(); String name = target.getGameProfile().getName();
            buttons.add(button(Items.PLAYER_HEAD, name, p -> {
                require(p, claim); manager().update(claim, c -> { c.members.put(uuid, ClaimRole.TRUSTED); c.memberNames.put(uuid, name); });
                members(p, manager().byId(claim.id));
            }, "点击添加为可信成员"));
        }
        MenuGui.open(player, "选择地皮成员", buttons, 0, p -> members(p, claim));
    }
    public static void openAdmin(ServerPlayer player) {
        if (!admin(player)) { MessageUtil.sendError(player, "地皮", "需要地皮管理权限"); return; }
        PlotRules rules = manager().rules();
        MenuGui.openSlots(player, "地皮管理 · 实时保存", 3, Map.of(
                10, button(Items.GOLD_INGOT, "后续每块购地价格", p -> GuiInput.open(p, "后续地皮价格", String.valueOf(rules.additionalPlotPrice), () -> admin(p), value -> {
                    manager().updateRules(r -> r.additionalPlotPrice = Math.round(GuiInput.number(value, 0.01, 1e12) * 100) / 100.0); openAdmin(p);
                }, () -> openAdmin(p)), EconomyManager.get().format(rules.additionalPlotPrice), "首块永久免费，修改仅影响后续购地"),
                12, button(Items.PAPER, "个人地皮数量上限", p -> GuiInput.open(p, "上限（0为不限）", String.valueOf(rules.maxPlotsPerPlayer), () -> admin(p), value -> {
                    manager().updateRules(r -> r.maxPlotsPerPlayer = GuiInput.integer(value, 0, 100000)); openAdmin(p);
                }, () -> openAdmin(p)), String.valueOf(rules.maxPlotsPerPlayer), "0为不限；降低上限不删除已有地皮"),
                14, button(Items.MAP, "管理全部地皮", p -> { if (admin(p)) list(p, true); }, "已售出 " + manager().all().size() + " 块"),
                18, button(Items.ARROW, "返回", PlotGui::open)));
    }
}
