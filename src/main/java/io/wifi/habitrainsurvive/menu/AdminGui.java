package io.wifi.habitrainsurvive.menu;

import io.wifi.habitrainsurvive.claim.*;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.*;
import io.wifi.habitrainsurvive.landmark.LandmarkGui;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.plot.*;
import io.wifi.habitrainsurvive.title.TitleManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import io.wifi.habitrainsurvive.world.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Editable server controls; player-command links never elevate the command source. */
public final class AdminGui {
    private AdminGui() { }
    private static boolean allowed(ServerPlayer player) { return PermissionManager.get().hasPermission(player, "habi.admin"); }
    private static MenuGui.Button button(Item icon, String title, Consumer<ServerPlayer> action, String... lore) {
        return new MenuGui.Button(MenuGui.icon(icon, title, lore), p -> {
            if (!allowed(p)) { MessageUtil.sendError(p, "管理", "管理员权限已失效"); p.closeContainer(); return; }
            try { action.accept(p); } catch (IllegalArgumentException e) { MessageUtil.sendError(p, "管理", e.getMessage()); }
        });
    }
    private static MenuGui.Button command(Item icon, String title, String command, String... lore) {
        return button(icon, title, p -> { p.closeContainer(); p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), command); }, lore);
    }
    private static void input(ServerPlayer player, String title, Object initial, Consumer<String> accepted, Runnable back) {
        GuiInput.open(player, title, String.valueOf(initial), () -> allowed(player), accepted, back);
    }
    public static void open(ServerPlayer player) {
        if (!allowed(player)) { MessageUtil.sendError(player, "管理", "需要管理员权限"); return; }
        Map<Integer, MenuGui.Button> slots = new HashMap<>();
        slots.put(10, command(Items.EMERALD, "动态系统商城编辑", "shop admin", "分类、商品、买卖价与动态幅度"));
        slots.put(11, command(Items.CHEST, "箱子商店管理", "qs admin", "全服商店、价格及经营状态"));
        slots.put(12, command(Items.ENDER_CHEST, "电子店铺配置", "playershop admin", "开店价格、升级费用、商品位与仓库容量"));
        slots.put(14, button(Items.COMPASS, "公共地标编辑", LandmarkGui::openAdmin, "新增、位置、图标、费用、开放开关"));
        slots.put(15, button(Items.GRASS_BLOCK, "地皮系统设置", PlotGui::openAdmin, "首块免费、后续价格、数量上限、全部地皮"));
        slots.put(16, command(Items.GOLD_BLOCK, "抽奖奖池编辑", "lottery admin", "使用已有奖池编辑器"));
        slots.put(19, button(Items.GOLD_INGOT, "经济与账户编辑", AdminGui::economy, "新账户初始金币、已有账户余额"));
        slots.put(20, button(Items.GOLDEN_HOE, "领地基础配置", AdminGui::claims, "圈地单价、默认配额与全服领地面板"));
        slots.put(21, button(Items.FILLED_MAP, "世界规则编辑", AdminGui::worlds, "开放、保护、费用、容量与时间"));
        slots.put(23, button(Items.NAME_TAG, "称号编辑", AdminGui::titles, "新增称号、名称、说明与购买价格"));
        slots.put(24, button(Items.PLAYER_HEAD, "权限组配额编辑", AdminGui::groups, "家、领地、拍卖配额与传送等待/冷却"));
        slots.put(25, command(Items.ITEM_FRAME, "自定义菜单编辑器", "menu edit", "创建菜单、编辑布局/按钮/动作，预览并保存"));
        slots.put(28, command(Items.COMPARATOR, "运行状态", "habi status"));
        slots.put(29, command(Items.OBSERVER, "重载运营配置", "habi reload", "世界新增、导入及启停仍需重启"));
        slots.put(30, command(Items.REDSTONE, "重载菜单", "menu reload"));
        slots.put(31, command(Items.WRITABLE_BOOK, "任务在线编辑", "quest web", "浏览器直接编辑并写回 quests.json，默认端口 11088"));
        slots.put(32, command(Items.BOOK, "重载本模组全部数据", "habi reloadall", "含任务、经济、权限等全部数据文件"));
        slots.put(33, button(Items.REPEATER, "模块开关管理", AdminGui::openModules, "单独启用或关闭箱子商店、城镇、领地等模块", "默认全部开启，保存至 modules.json"));
        slots.put(45, button(Items.ARROW, "返回原管理菜单", p -> MenuManager.get().open(p, "admin")));
        slots.put(49, button(Items.BARRIER, "关闭", ServerPlayer::closeContainer));
        MenuGui.openSlots(player, "系统管理 · 点击直接编辑", 6, slots);
    }

    public static void openModules(ServerPlayer player) {
        if (!allowed(player)) { MessageUtil.sendError(player, "管理", "需要管理员权限"); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (ModuleId id : ModuleId.values()) {
            boolean enabled = ModuleConfig.isEnabled(id);
            buttons.add(button(enabled ? Items.LIME_DYE : Items.RED_DYE,
                    id.displayName() + " (" + id.id() + "): " + (enabled ? "§a开启" : "§c关闭"),
                    p -> {
                        try {
                            boolean next = ModuleConfig.toggle(id);
                            MessageUtil.sendSuccess(p, "模块管理", "已将 [" + id.displayName() + "] 切换为 " + (next ? "开启" : "关闭")
                                    + "（监听器与定时任务即时生效；指令注册需重启生效）。");
                            openModules(p);
                        } catch (IOException e) {
                            MessageUtil.sendError(p, "模块管理", "保存失败: " + e.getMessage());
                        }
                    },
                    id.description(),
                    "点击切换状态 · 配置保存在 modules.json",
                    "注：指令注册变更需重启服务器生效"));
        }
        MenuGui.open(player, "模块开关管理 · 默认全部启用", buttons, 0, AdminGui::open);
    }
    private static void economy(ServerPlayer player) {
        if (!allowed(player)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.GOLD_BLOCK, "新账户初始金币", p -> input(p, "新账户初始金币", EconomyManager.get().getStartingBalance(), value -> {
            EconomyManager.get().setStartingBalance(GuiInput.number(value, 0, 1e12)); economy(p);
        }, () -> economy(p)), "当前: " + EconomyManager.get().format(EconomyManager.get().getStartingBalance()), "仅影响以后创建的账户"));
        for (EconomyAccount account : EconomyManager.get().getTopAccounts(Integer.MAX_VALUE)) {
            buttons.add(button(Items.PLAYER_HEAD, account.name, p -> input(p, "设置账户余额", EconomyManager.get().getBalance(account.uuid), value -> {
                double amount = Math.round(GuiInput.number(value, 0, 1e12) * 100) / 100.0;
                MenuGui.openSlots(p, "确认重设 " + account.name + " 的余额", 3, Map.of(
                        11, button(Items.RED_CONCRETE, "确认设置为 " + EconomyManager.get().format(amount), sp -> {
                            EconomyManager.get().setBalance(account.uuid, amount, "GUI admin " + sp.getGameProfile().getName());
                            ServerDataManager.saveAll(); economy(sp);
                        }, "重设，不是追加；操作写入经济账本"),
                        15, button(Items.LIME_CONCRETE, "取消", AdminGui::economy)));
            }, () -> economy(p)), EconomyManager.get().format(account.balance), account.uuid.toString(), "点击编辑；提交后仍需确认"));
        }
        MenuGui.open(player, "经济管理", buttons, 0, AdminGui::open);
    }
    private static void claims(ServerPlayer player) {
        if (!allowed(player)) return;
        ClaimManager manager = ClaimManager.get();
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.GOLD_INGOT, "圈地每平方格单价", p -> input(p, "圈地单价", manager.getCostPerBlock2D(), value -> {
            manager.configure(GuiInput.number(value, 0, 1e9), manager.getDefaultMaxClaims()); claims(p);
        }, () -> claims(p)), EconomyManager.get().format(manager.getCostPerBlock2D()), "不影响已付费领地的边界"));
        buttons.add(button(Items.PAPER, "普通领地基础配额", p -> input(p, "默认领地数量", manager.getDefaultMaxClaims(), value -> {
            manager.configure(manager.getCostPerBlock2D(), GuiInput.integer(value, 1, 100000)); claims(p);
        }, () -> claims(p)), String.valueOf(manager.getDefaultMaxClaims()), "实际配额为此值与权限组配额的较大值"));
        manager.getAllClaims().stream().sorted(Comparator.comparing(c -> c.name)).forEach(claim -> buttons.add(button(Items.GRASS_BLOCK,
                claim.name, p -> ClaimControlPanelGui.openClaimDashboard(p, claim), "领主: " + claim.ownerName, "点击现有领地控制面板（原OP权限仍适用）")));
        MenuGui.open(player, "领地管理", buttons, 0, AdminGui::open);
    }
    private static void titles(ServerPlayer player) {
        if (!allowed(player)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.EMERALD, "新增称号", p -> input(p, "称号ID（字母数字）", "new_title", value -> {
            String id = io.wifi.habitrainsurvive.landmark.LandmarkManager.id(value);
            var def = new TitleManager.TitleDef(id, "[新称号]", 1000, "自定义称号");
            if (TitleManager.get().getTitles().putIfAbsent(id, def) != null) throw new IllegalArgumentException("称号ID已存在");
            ServerDataManager.saveAll(); title(p, def);
        }, () -> titles(p))));
        TitleManager.get().getTitles().values().forEach(def -> buttons.add(button(Items.NAME_TAG, def.display, p -> title(p, def),
                def.description == null ? "" : def.description, "价格: " + EconomyManager.get().format(def.price))));
        MenuGui.open(player, "称号管理", buttons, 0, AdminGui::open);
    }
    private static void title(ServerPlayer player, TitleManager.TitleDef def) {
        if (!allowed(player) || TitleManager.get().getTitles().get(def.id) != def) return;
        Runnable back = () -> title(player, def);
        BooleanSupplier valid = () -> allowed(player) && TitleManager.get().getTitles().get(def.id) == def;
        MenuGui.openSlots(player, "编辑称号 · " + def.id, 3, Map.of(
                10, button(Items.NAME_TAG, "称号显示文本", p -> GuiInput.open(p, "显示文本（支持&颜色）", def.display, valid, value -> {
                    def.display = GuiInput.text(value, 64).replace('&', '§'); ServerDataManager.saveAll(); back.run();
                }, back), def.display),
                12, button(Items.GOLD_INGOT, "解锁价格", p -> GuiInput.open(p, "解锁价格", String.valueOf(def.price), valid, value -> {
                    def.price = Math.round(GuiInput.number(value, 0, 1e12) * 100) / 100.0; ServerDataManager.saveAll(); back.run();
                }, back), EconomyManager.get().format(def.price)),
                14, button(Items.WRITABLE_BOOK, "说明", p -> GuiInput.open(p, "称号说明", def.description == null ? "无说明" : def.description, valid, value -> {
                    def.description = GuiInput.text(value, 160); ServerDataManager.saveAll(); back.run();
                }, back), def.description == null ? "" : def.description),
                18, button(Items.ARROW, "返回", AdminGui::titles)));
    }
    private record Toggle(String name, Predicate<WorldDefinition> get, BiConsumer<WorldDefinition, Boolean> set, boolean managed) { }
    private static final List<Toggle> TOGGLES = List.of(
            new Toggle("世界开放", w -> w.open, (w, v) -> w.open = v, false),
            new Toggle("建造", w -> w.build, (w, v) -> w.build = v, false),
            new Toggle("交互", w -> w.interact, (w, v) -> w.interact = v, false),
            new Toggle("PVP", w -> w.pvp, (w, v) -> w.pvp = v, false),
            new Toggle("玩家受伤", w -> w.damage, (w, v) -> w.damage = v, false),
            new Toggle("饥饿", w -> w.hunger, (w, v) -> w.hunger = v, false),
            new Toggle("永久圈地", w -> w.allowClaims, (w, v) -> w.allowClaims = v, false),
            new Toggle("箱子商店", w -> w.allowShops, (w, v) -> w.allowShops = v, false),
            new Toggle("生物刷新", w -> w.mobs, (w, v) -> w.mobs = v, true),
            new Toggle("生物破坏", w -> w.mobGrief, (w, v) -> w.mobGrief = v, true),
            new Toggle("爆炸", w -> w.explosions, (w, v) -> w.explosions = v, false),
            new Toggle("火焰蔓延", w -> w.fireSpread, (w, v) -> w.fireSpread = v, false),
            new Toggle("流体流动", w -> w.fluids, (w, v) -> w.fluids = v, false),
            new Toggle("活塞", w -> w.pistons, (w, v) -> w.pistons = v, false),
            new Toggle("天气", w -> w.weather, (w, v) -> w.weather = v, true));
    private static void worlds(ServerPlayer player) {
        if (!allowed(player)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (WorldDefinition world : WorldManager.get().configuredCopy().worlds) buttons.add(button(Items.FILLED_MAP, world.name,
                p -> world(p, world.id), world.id, "点击编辑规则（即时生效）", "生成器、启停、导入使用原专用命令并重启"));
        MenuGui.open(player, "世界规则管理", buttons, 0, AdminGui::open);
    }
    private static void changeWorld(String id, Consumer<WorldDefinition> change) {
        try { WorldManager.get().update(config -> change.accept(config.worlds.stream().filter(w -> w.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("世界已不存在")))); }
        catch (IOException e) { throw new IllegalArgumentException("世界配置保存失败: " + e.getMessage()); }
    }
    private static void world(ServerPlayer player, String id) {
        if (!allowed(player)) return;
        WorldDefinition world = WorldManager.get().configuredCopy().worlds.stream().filter(w -> w.id.equals(id)).findFirst().orElse(null);
        if (world == null) { worlds(player); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (Toggle toggle : TOGGLES) {
            if (toggle.managed && !world.managed() || PlotGrid.WORLD.equals(id) && toggle.name.equals("永久圈地")) continue;
            buttons.add(button(toggle.get.test(world) ? Items.LIME_DYE : Items.RED_DYE, toggle.name + ": " + (toggle.get.test(world) ? "开启" : "关闭"), p -> {
                changeWorld(id, w -> toggle.set.accept(w, !toggle.get.test(w))); world(p, id);
            }, "点击切换；世界规则优先于领地规则"));
        }
        buttons.add(button(Items.GOLD_INGOT, "进入费用", p -> input(p, "世界进入费", world.entryFee, value -> {
            changeWorld(id, w -> w.entryFee = Math.round(GuiInput.number(value, 0, 1e12) * 100) / 100.0); world(p, id);
        }, () -> world(p, id)), EconomyManager.get().format(world.entryFee)));
        buttons.add(button(Items.PLAYER_HEAD, "在线人数上限", p -> input(p, "人数上限（0为不限）", world.playerLimit, value -> {
            changeWorld(id, w -> w.playerLimit = GuiInput.integer(value, 0, 100000)); world(p, id);
        }, () -> world(p, id)), String.valueOf(world.playerLimit)));
        if (world.managed()) buttons.add(button(Items.CLOCK, "固定时间", p -> input(p, "时间（-1正常流逝）", world.fixedTime, value -> {
            changeWorld(id, w -> w.fixedTime = GuiInput.integer(value, -1, 24000)); world(p, id);
        }, () -> world(p, id)), String.valueOf(world.fixedTime)));
        MenuGui.open(player, "规则 · " + world.name, buttons, 0, AdminGui::worlds);
    }
    private static void groups(ServerPlayer player) {
        if (!allowed(player)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        PermissionManager.get().getGroups().forEach((id, group) -> buttons.add(button(Items.PLAYER_HEAD, id, p -> group(p, id, group),
                "家 " + group.maxHomes + " / 领地 " + group.maxClaims + " / 拍卖 " + group.maxAuctionListings,
                "传送等待 " + group.teleportWarmupSeconds + "秒 / 随机传送冷却 " + group.rtpCooldownSeconds + "秒")));
        MenuGui.open(player, "权限组配额", buttons, 0, AdminGui::open);
    }
    private static void group(ServerPlayer player, String id, PermissionManager.RankGroup group) {
        if (!allowed(player) || PermissionManager.get().getGroups().get(id) != group) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        String[] names = {"家数量", "领地数量", "拍卖数量", "传送读条秒数", "随机传送冷却秒数"};
        int[] values = {group.maxHomes, group.maxClaims, group.maxAuctionListings, group.teleportWarmupSeconds, group.rtpCooldownSeconds};
        for (int i = 0; i < names.length; i++) {
            int field = i;
            buttons.add(button(Items.PAPER, names[i], p -> GuiInput.open(p, names[field], String.valueOf(values[field]),
                    () -> allowed(p) && PermissionManager.get().getGroups().get(id) == group, value -> {
                        int number = GuiInput.integer(value, 0, field < 3 ? 100000 : 86400);
                        switch (field) {
                            case 0 -> group.maxHomes = number; case 1 -> group.maxClaims = number; case 2 -> group.maxAuctionListings = number;
                            case 3 -> group.teleportWarmupSeconds = number; case 4 -> group.rtpCooldownSeconds = number; default -> throw new IllegalStateException();
                        }
                        ServerDataManager.saveAll(); group(p, id, group);
                    }, () -> group(p, id, group)), "当前: " + values[i], "只修改配额，不授予额外命令权限"));
        }
        MenuGui.open(player, "权限组 · " + id, buttons, 0, AdminGui::groups);
    }
}
