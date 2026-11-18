package io.wifi.habitrainsurvive.sysshop;

import io.wifi.habitrainsurvive.menu.GuiInput;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.*;

/** Separate quota editor keeps pricing and category screens readable. Zero always means unlimited. */
public final class SystemShopLimitsGui {
    private SystemShopLimitsGui() { }
    private record Field(String label, ToIntFunction<ShopPurchaseLimits> get, ObjIntConsumer<ShopPurchaseLimits> set) { }
    private static final List<Field> FIELDS = List.of(
            new Field("单次", l -> l.perTransaction, (l, v) -> l.perTransaction = v),
            new Field("每日", l -> l.daily, (l, v) -> l.daily = v),
            new Field("每周", l -> l.weekly, (l, v) -> l.weekly = v),
            new Field("每月", l -> l.monthly, (l, v) -> l.monthly = v),
            new Field("累计", l -> l.total, (l, v) -> l.total = v));
    private static SystemShopManager manager() { return SystemShopManager.get(); }
    private static boolean valid(ServerPlayer player, SystemShopCategory category, SystemShopItem item) {
        return manager().authorized(player) && manager().isCurrent(category) && (item == null || manager().isCurrent(category, item));
    }
    private static MenuGui.Button button(SystemShopCategory category, SystemShopItem item, Item icon, String title, Consumer<ServerPlayer> action, String... lore) {
        return new MenuGui.Button(MenuGui.icon(icon, title, lore), player -> {
            if (!valid(player, category, item)) {
                MessageUtil.sendError(player, "商城限购", "权限或商品/分类已变化，请重新进入 /shop admin"); player.closeContainer(); return;
            }
            try { action.accept(player); } catch (IllegalArgumentException e) { MessageUtil.sendError(player, "商城限购", e.getMessage()); }
        });
    }
    public static void open(ServerPlayer player, SystemShopCategory category, SystemShopItem item) {
        if (!valid(player, category, item)) { MessageUtil.sendError(player, "商城限购", "需要管理权限，且目标必须仍存在"); return; }
        ShopPurchasePolicy policy = item == null ? category.purchaseLimits : item.purchaseLimits;
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(category, item, Items.PAPER, item == null ? "分类合计限购" : "单品限购", p -> { },
                "个人与全服、分类与单品同时检查，任一不足整笔拒绝", "按购买件数统计，不按交易次数或金额统计",
                "0表示不限；卖回物品不返还额度", "时区: " + manager().purchaseZone().getId() + " · 每周一0点刷新周额度"));
        for (boolean shared : new boolean[]{false, true}) {
            ShopPurchaseLimits limits = shared ? policy.global : policy.personal;
            for (Field field : FIELDS) {
                int initial = field.get.applyAsInt(limits);
                String title = (shared ? "全服共享 · " : "每位玩家 · ") + field.label + "上限";
                buttons.add(button(category, item, shared ? Items.BEACON : Items.PLAYER_HEAD, title + ": " + (initial == 0 ? "不限" : initial), p ->
                        GuiInput.open(p, title + "（0不限）", String.valueOf(initial), () -> valid(p, category, item), value -> {
                            int amount = GuiInput.integer(value, 0, ShopPurchaseLimits.MAX);
                            if (item == null) manager().editCategoryLimits(p, category, draft -> field.set.accept(shared ? draft.global : draft.personal, amount));
                            else manager().editItem(p, category, item, draft -> field.set.accept(shared ? draft.purchaseLimits.global : draft.purchaseLimits.personal, amount));
                            open(p, category, item);
                        }, () -> open(p, category, item)), "点击设置购买件数；0不限", "修改上限不会清除已使用额度"));
            }
        }
        buttons.add(button(category, item, Items.MILK_BUCKET, "重置本范围全部购买计数…", p -> confirmReset(p, category, item),
                "清除该范围的个人及全服日/周/月/累计统计", item == null ? "只重置分类合计，不影响各单品计数" : "只重置本单品，不影响分类合计", "需要再次确认，操作立即保存"));
        MenuGui.open(player, "限购配置 · " + (item == null ? category.displayName : item.displayName), buttons, 0,
                p -> { if (item == null) SystemShopAdminGui.openCategory(p, category, 0); else SystemShopAdminGui.openItem(p, category, item, 0); });
    }
    private static void confirmReset(ServerPlayer player, SystemShopCategory category, SystemShopItem item) {
        List<MenuGui.Button> buttons = List.of(
                button(category, item, Items.RED_CONCRETE, "确认清除该范围全部计数", p -> { manager().resetPurchaseUsage(p, category, item); open(p, category, item); }),
                button(category, item, Items.LIME_CONCRETE, "取消", p -> open(p, category, item)));
        MenuGui.open(player, "确认重置限购计数？", buttons, 0, p -> open(p, category, item));
    }
}
