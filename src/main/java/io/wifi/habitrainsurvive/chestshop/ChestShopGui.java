package io.wifi.habitrainsurvive.chestshop;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.GuiInput;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;
import java.util.function.Consumer;

public final class ChestShopGui {
    private static ChestShopManager manager() { return ChestShopManager.get(); }
    private static MenuGui.Button button(net.minecraft.world.item.Item item, String name, Consumer<ServerPlayer> action, String... lore) {
        return new MenuGui.Button(MenuGui.icon(item, name, lore), action);
    }
    private static String money(double value) { return EconomyManager.get().format(value); }
    private static String location(ChestShop shop) { return shop.dimension + " (" + shop.x + ", " + shop.y + ", " + shop.z + ")"; }

    /** Explicit opt-in before any price input or persistent creation. */
    public static void confirmCreate(ServerPlayer player, BlockPos target) {
        BlockPos pos = target.immutable();
        String dim = player.serverLevel().dimension().location().toString();
        BlockEntity expected = player.serverLevel().getBlockEntity(pos);
        ItemStack template = player.getMainHandItem().copyWithCount(1);
        if (template.isEmpty() || !manager().canCreate(player, dim, pos, expected, true)) return;
        MenuGui.openSlots(player, "是否创建箱子商店？", 3, Map.of(
                11, button(Items.LIME_CONCRETE, "是：设置商品价格", p -> {
                    if (manager().canCreate(p, dim, pos, expected, true)) createPrice(p, dim, pos, expected, template, true, 0);
                }, "商品: " + template.getHoverName().getString(), "位置: " + pos.toShortString(), "样品不消耗；不会移动箱内物品"),
                15, button(Items.RED_CONCRETE, "否 / 取消", ServerPlayer::closeContainer)));
    }
    private static void createPrice(ServerPlayer p, String dim, BlockPos pos, BlockEntity expected, ItemStack item, boolean buy, double buyPrice) {
        GuiInput.open(p, buy ? "出售单价（0=不出售）" : "收购单价（0=不收购）", "0",
                () -> manager().canCreate(p, dim, pos, expected, false), text -> {
                    Double value = GuiInput.number(text, 0, ShopTradeRules.MAX_PRICE);
                    if (value == null || !ShopTradeRules.price(value, true)) { MessageUtil.sendError(p, "箱子商店", "请输入 0 或至少 0.01 的有限价格。"); createPrice(p, dim, pos, expected, item, buy, buyPrice); return; }
                    if (buy) createPrice(p, dim, pos, expected, item, false, value);
                    else {
                        ChestShop shop = manager().create(p, dim, pos, expected, item, buyPrice, value);
                        if (shop != null) openOwner(p, shop);
                        else { MessageUtil.sendError(p, "箱子商店", "创建失败：价格不能同时为零，或目标/权限已改变。"); p.closeContainer(); }
                    }
                }, p::closeContainer);
    }

    public static void open(ServerPlayer player, ChestShop shop) {
        if (!manager().isCurrent(shop)) { openBrowser(player, 1); return; }
        if (manager().canManage(player, shop)) { openOwner(player, shop); return; }
        Container expected = manager().resolveContainer(player, shop);
        ItemStack t = ItemSerializer.deserialize(shop.serializedItem, player.server.registryAccess());
        double buy = shop.buyPrice, sell = shop.sellPrice;
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.CHEST, shop.itemDisplayName, p -> open(p, shop), "店主: " + shop.ownerName,
                "库存: " + (shop.adminInfinite ? "无限" : manager().countStock(expected, t)), location(shop), "交易须在原箱6格内；访客无需开箱或建造权限"));
        buttons.add(button(Items.COMPASS, "位置 / 前往商店", p -> teleport(p, shop), location(shop)));
        if (expected == null) buttons.add(button(Items.BARRIER, "容器不可用", p -> open(p, shop), "旧商店需店主靠近并打开管理面板绑定原箱", "箱子被替换、未加载或合并成双箱时暂停交易"));
        for (int amount : new int[]{1, 16, t.isEmpty() ? 64 : t.getMaxStackSize()}) {
            if (buy > 0) buttons.add(button(Items.EMERALD, "购买 " + amount + " 件", p -> {
                if (manager().isCurrent(shop) && shop.buyPrice == buy && expected != null) manager().buyFromShop(p, shop, amount, expected);
                open(p, shop);
            }, "总价: " + money(buy * amount)));
            if (sell > 0) buttons.add(button(Items.GOLD_INGOT, "出售 " + amount + " 件", p -> {
                if (manager().isCurrent(shop) && shop.sellPrice == sell && expected != null) manager().sellToShop(p, shop, amount, expected);
                open(p, shop);
            }, "收入: " + money(sell * amount)));
        }
        MenuGui.open(player, "箱子商店: " + shop.itemDisplayName, buttons, 0, p -> openBrowser(p, 1));
    }

    public static void openOwner(ServerPlayer player, ChestShop shop) {
        if (!manager().canManage(player, shop)) { openMyShops(player); return; }
        ItemStack t = ItemSerializer.deserialize(shop.serializedItem, player.server.registryAccess());
        Container box = manager().resolveContainer(player, shop);
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.CHEST, shop.itemDisplayName + " · 库存 " + manager().countStock(box, t), p -> {
            if (manager().canManage(p, shop)) openOwner(p, shop);
        }, location(shop), "右键原容器补货/取货（沿用领地/城镇权限）", "售价: " + money(shop.buyPrice), "收购价: " + money(shop.sellPrice)));
        buttons.add(button(Items.EMERALD, "编辑出售单价", p -> editPrice(p, shop, true)));
        buttons.add(button(Items.GOLD_INGOT, "编辑收购单价", p -> editPrice(p, shop, false)));
        buttons.add(button(Items.COMPASS, "位置 / 前往商店", p -> teleport(p, shop), location(shop)));
        if (manager().canDelete(player, shop)) buttons.add(button(Items.BARRIER, "删除商店…", p -> confirmDelete(p, shop)));
        if (player.hasPermissions(2)) buttons.add(button(Items.COMMAND_BLOCK, "无限库存: " + shop.adminInfinite, p -> {
            if (p.hasPermissions(2) && manager().isCurrent(shop)) {
                shop.adminInfinite = !shop.adminInfinite;
                manager().saveChanges();
                openOwner(p, shop);
            }
        }, "仅管理员；系统买卖不使用店主余额"));
        MenuGui.open(player, "管理箱子商店", buttons, 0, ChestShopGui::openMyShops);
    }
    private static void editPrice(ServerPlayer player, ChestShop shop, boolean buy) {
        if (!manager().canManage(player, shop)) return;
        GuiInput.open(player, buy ? "出售单价（0=禁用）" : "收购单价（0=禁用）", String.valueOf(buy ? shop.buyPrice : shop.sellPrice),
                () -> manager().canManage(player, shop), text -> {
                    Double price = GuiInput.number(text, 0, ShopTradeRules.MAX_PRICE);
                    if (price == null || !manager().setPrices(player, shop, buy ? price : shop.buyPrice, buy ? shop.sellPrice : price))
                        MessageUtil.sendError(player, "箱子商店", "无效价格，不能同时禁用两种交易。");
                    openOwner(player, shop);
                }, () -> openOwner(player, shop));
    }
    public static void confirmDelete(ServerPlayer player, ChestShop shop) {
        if (!manager().canDelete(player, shop)) return;
        MenuGui.openSlots(player, "确认删除商店？箱内物品保留", 3, Map.of(
                11, button(Items.RED_CONCRETE, "确认删除", p -> { manager().delete(p, shop); openMyShops(p); }, location(shop)),
                15, button(Items.OAK_DOOR, "取消", p -> openOwner(p, shop))));
    }
    private static void teleport(ServerPlayer p, ChestShop shop) {
        if (!manager().isCurrent(shop)) return;
        p.closeContainer();
        TeleportWarmupManager.get().scheduleTeleportToPos(p, shop.ownerName + " 的商店",
                new GlobalPosInfo(shop.dimension, shop.x + 0.5, shop.y + 1.0, shop.z + 0.5, p.getYRot(), p.getXRot()));
    }
    public static void openMyShops(ServerPlayer player) { list(player, 1, true, false); }
    public static void openAdmin(ServerPlayer player) { if (player.hasPermissions(2)) list(player, 1, false, true); }
    public static void openBrowser(ServerPlayer player, int page) { list(player, page, false, false); }
    private static void list(ServerPlayer player, int page, boolean mine, boolean admin) {
        if (admin && !player.hasPermissions(2)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        if (!mine && !admin) buttons.add(button(Items.ENDER_CHEST, "我的箱子商店", ChestShopGui::openMyShops));
        manager().getAllShops().stream().filter(s -> !mine || s.canManage(player.getUUID()))
                .sorted(Comparator.comparing(ChestShop::getPosKey)).forEach(shop -> buttons.add(button(Items.CHEST, shop.itemDisplayName,
                        p -> { if ((!admin || p.hasPermissions(2)) && manager().isCurrent(shop)) open(p, shop); },
                        "店主: " + shop.ownerName, location(shop), "售价: " + money(shop.buyPrice), "收购价: " + money(shop.sellPrice))));
        MenuGui.open(player, admin ? "管理员 · 箱子商店" : mine ? "我的箱子商店" : "全服箱子商店", buttons, page - 1,
                mine || admin ? p -> openBrowser(p, 1) : null);
    }
}
