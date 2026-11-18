package io.wifi.habitrainsurvive.playershop;

import io.wifi.habitrainsurvive.chestshop.ShopTradeRules;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.GuiInput;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.*;
import java.util.function.Consumer;

/** All icons are copies in MenuGui's frozen inventory, never a second source of real items. */
public final class PlayerShopGui {
    private static PlayerShopManager manager() { return PlayerShopManager.get(); }
    private static String money(double amount) { return EconomyManager.get().format(amount); }
    private static MenuGui.Button button(Item item, String title, Consumer<ServerPlayer> action, String... lore) {
        return new MenuGui.Button(MenuGui.icon(item, title, lore), action);
    }
    private static MenuGui.Button itemButton(ItemStack template, String title, Consumer<ServerPlayer> action, String... lore) {
        ItemStack icon = template.isEmpty() ? new ItemStack(Items.PAPER) : template.copyWithCount(1);
        icon.set(DataComponents.CUSTOM_NAME, Component.literal(title));
        icon.set(DataComponents.LORE, new ItemLore(Arrays.stream(lore).map(s -> (Component) Component.literal(s)).toList()));
        return new MenuGui.Button(icon, action);
    }
    private static ItemStack template(ServerPlayer p, PlayerShop.Listing listing) {
        return ItemSerializer.deserialize(listing.serializedItem, p.server.registryAccess());
    }
    private static String capacity(PlayerShopConfig.Level level) { return level.listings() + " 种商品 / " + level.capacity() + " 件物品"; }

    public static void open(ServerPlayer player) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.ENDER_CHEST, "我的电子商店", PlayerShopGui::openMine, "一个 UUID 只能开一家；可离线销售"));
        if (player.hasPermissions(2)) buttons.add(button(Items.COMMAND_BLOCK, "管理员配置", PlayerShopGui::openAdmin));
        manager().shops().stream().sorted(Comparator.comparing(s -> s.ownerUuid.toString())).forEach(shop ->
                buttons.add(button(Items.CHEST, shop.ownerName + " 的电子商店 · L" + shop.level, p -> openShop(p, shop),
                        "商品种类: " + shop.listings.size(), "库存总数: " + shop.stock(), "点击浏览商品；随时随地交易")));
        MenuGui.open(player, "全服电子商店", buttons, 0, null);
    }
    private static void openShop(ServerPlayer player, PlayerShop shop) {
        if (!manager().current(shop)) { open(player); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (PlayerShop.Listing listing : List.copyOf(shop.listings.values())) {
            long revision = listing.revision;
            buttons.add(itemButton(template(player, listing), listing.displayName, p -> {
                if (manager().listingCurrent(shop, listing, revision)) openTrade(p, shop, listing);
                else { stale(p); openShop(p, shop); }
            }, "库存: " + listing.quantity, "每件: " + money(listing.unitPrice)));
        }
        MenuGui.open(player, shop.ownerName + " 的电子商店", buttons, 0, PlayerShopGui::open);
    }
    private static void openTrade(ServerPlayer player, PlayerShop shop, PlayerShop.Listing listing) {
        long revision = listing.revision;
        if (!manager().listingCurrent(shop, listing, revision)) { openShop(player, shop); return; }
        ItemStack t = template(player, listing);
        if (t.isEmpty()) { MessageUtil.sendError(player, "电子商店", "此商品组件无法读取，库存仍保留。"); openShop(player, shop); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(itemButton(t, listing.displayName, p -> openShop(p, shop), "库存: " + listing.quantity, "单价: " + money(listing.unitPrice)));
        for (int amount : new int[]{1, t.getMaxStackSize()}) buttons.add(button(Items.EMERALD, "购买 " + amount + " 件", p -> {
            manager().buy(p, shop, listing, revision, amount);
            if (manager().listingCurrent(shop, listing, listing.revision)) openTrade(p, shop, listing);
            else openShop(p, shop);
        }, "总价: " + money(listing.unitPrice * amount), "必须有足够背包空间；不能自购", "库存/价格变化后旧按钮失效"));
        MenuGui.open(player, "购买商品", buttons, 0, p -> openShop(p, shop));
    }

    public static void openMine(ServerPlayer player) {
        PlayerShop shop = manager().getShop(player.getUUID());
        if (shop == null) { openOpening(player); return; }
        if (!manager().owns(player, shop)) { player.closeContainer(); return; }
        PlayerShopConfig config = manager().config();
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.CHEST, "我的商店 · L" + shop.level, PlayerShopGui::openMine,
                "已用: " + shop.listings.size() + " 种 / " + shop.stock() + " 件", "上限: " + capacity(config.level(shop.level))));
        buttons.add(button(Items.HOPPER, "从背包入库", p -> openDeposit(p, shop), "只移动真实背包内物品，完整保存组件"));
        buttons.add(button(Items.EXPERIENCE_BOTTLE, shop.level < 5 ? "升级商店…" : "已达最高等级", p -> openUpgrade(p, shop)));
        for (PlayerShop.Listing listing : List.copyOf(shop.listings.values())) {
            long revision = listing.revision;
            buttons.add(itemButton(template(player, listing), listing.displayName, p -> {
                if (manager().owns(p, shop) && manager().listingCurrent(shop, listing, revision)) openListing(p, shop, listing);
                else { stale(p); openMine(p); }
            }, "库存: " + listing.quantity, "单价: " + money(listing.unitPrice), "点击改价 / 取货 / 移除空商品"));
        }
        MenuGui.open(player, "我的电子商店", buttons, 0, PlayerShopGui::open);
    }
    private static void openOpening(ServerPlayer player) {
        PlayerShopConfig config = manager().config();
        long quote = manager().configRevision();
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.LIME_CONCRETE, "确认开店：" + money(config.openingCost), p -> {
            manager().openShop(p, quote);
            openMine(p);
        }, "基础等级 L1: " + capacity(config.level(1)), "一个 UUID 仅一家；确认后扣费"));
        for (int i = 2; i <= 5; i++) {
            var limits = config.level(i);
            buttons.add(button(Items.PAPER, "L" + i + " 升级费用: " + money(limits.upgradeCost()), p -> { }, capacity(limits), "逐级升级，每次单独确认"));
        }
        MenuGui.open(player, "开设电子商店？", buttons, 0, PlayerShopGui::open);
    }
    private static void openUpgrade(ServerPlayer player, PlayerShop shop) {
        if (!manager().owns(player, shop)) return;
        if (shop.level >= 5) { openMine(player); return; }
        int oldLevel = shop.level;
        long quote = manager().configRevision();
        var next = manager().config().level(oldLevel + 1);
        MenuGui.openSlots(player, "确认升级至 L" + (oldLevel + 1) + "？", 3, Map.of(
                11, button(Items.LIME_CONCRETE, "确认升级：" + money(next.upgradeCost()), p -> {
                    manager().upgrade(p, shop, oldLevel, quote);
                    openMine(p);
                }, "升级后上限: " + capacity(next), "库存原样保留；仅扣此次升级费用"),
                15, button(Items.OAK_DOOR, "取消", PlayerShopGui::openMine)));
    }

    private static void openDeposit(ServerPlayer player, PlayerShop shop) {
        if (!manager().owns(player, shop)) { openMine(player); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            int slot = i;
            ItemStack source = player.getInventory().getItem(slot).copy();
            if (source.isEmpty()) continue;
            buttons.add(itemButton(source, source.getHoverName().getString() + " ×" + source.getCount(),
                    p -> depositAmount(p, shop, slot, source), "背包槽位 " + (slot + 1), "点击输入入库数量；不会取走界面图标"));
        }
        MenuGui.open(player, "选择背包物品入库", buttons, 0, PlayerShopGui::openMine);
    }
    private static boolean sourceValid(ServerPlayer p, PlayerShop shop, int slot, ItemStack expected) {
        ItemStack source = p.getInventory().getItem(slot);
        return manager().owns(p, shop) && !source.isEmpty() && ItemStack.isSameItemSameComponents(source, expected);
    }
    private static void depositAmount(ServerPlayer player, PlayerShop shop, int slot, ItemStack source) {
        if (!sourceValid(player, shop, slot, source)) { openMine(player); return; }
        GuiInput.open(player, "入库数量 (1.." + source.getCount() + ")", String.valueOf(source.getCount()),
                () -> sourceValid(player, shop, slot, source), text -> {
                    Integer amount = GuiInput.integer(text, 1, source.getCount());
                    if (amount == null) { stale(player); openDeposit(player, shop); return; }
                    for (PlayerShop.Listing listing : shop.listings.values()) {
                        if (ItemStack.isSameItemSameComponents(template(player, listing), source)) {
                            manager().deposit(player, shop, slot, source, amount, listing.unitPrice);
                            openMine(player);
                            return;
                        }
                    }
                    GuiInput.open(player, "新商品每件售价 (至少 0.01)", "1", () -> sourceValid(player, shop, slot, source), priceText -> {
                        Double price = GuiInput.number(priceText, 0.01, ShopTradeRules.MAX_PRICE);
                        if (price != null) manager().deposit(player, shop, slot, source, amount, price);
                        else MessageUtil.sendError(player, "电子商店", "无效价格，物品未入库。");
                        openMine(player);
                    }, () -> openDeposit(player, shop));
                }, () -> openDeposit(player, shop));
    }
    private static void openListing(ServerPlayer player, PlayerShop shop, PlayerShop.Listing listing) {
        long revision = listing.revision;
        if (!manager().owns(player, shop) || !manager().listingCurrent(shop, listing, revision)) { openMine(player); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        ItemStack t = template(player, listing);
        buttons.add(itemButton(t, listing.displayName, p -> openMine(p), "库存: " + listing.quantity, "单价: " + money(listing.unitPrice)));
        buttons.add(button(Items.EMERALD, "编辑每件售价", p -> {
            if (!manager().owns(p, shop) || !manager().listingCurrent(shop, listing, revision)) { stale(p); openMine(p); return; }
            GuiInput.open(p, "商品每件售价", String.valueOf(listing.unitPrice),
                    () -> manager().owns(p, shop) && manager().listingCurrent(shop, listing, revision), text -> {
                        Double price = GuiInput.number(text, 0.01, ShopTradeRules.MAX_PRICE);
                        if (price != null) manager().setPrice(p, shop, listing, revision, price);
                        else MessageUtil.sendError(p, "电子商店", "价格无效。");
                        openMine(p);
                    }, () -> openMine(p));
        }));
        for (int amount : new LinkedHashSet<>(List.of(1, t.isEmpty() ? 64 : t.getMaxStackSize(), listing.quantity))) {
            if (amount <= 0) continue;
            buttons.add(button(Items.DROPPER, "取出 " + amount + " 件", p -> {
                manager().withdraw(p, shop, listing, revision, amount);
                openMine(p);
            }, "需背包有足够空间；不掉落溢出物品"));
        }
        buttons.add(button(Items.BARRIER, "移除空商品", p -> {
            manager().removeListing(p, shop, listing, revision);
            openMine(p);
        }, "库存必须为 0；有库存时绝不会删除"));
        MenuGui.open(player, "管理商品", buttons, 0, PlayerShopGui::openMine);
    }

    public static void openAdmin(ServerPlayer player) {
        if (player.hasPermissions(2)) openAdminDraft(player, manager().config(), manager().configRevision());
    }
    private static void openAdminDraft(ServerPlayer player, PlayerShopConfig draft, long revision) {
        if (!player.hasPermissions(2)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.EMERALD_BLOCK, "开店费用: " + money(draft.openingCost), p -> editConfig(p, draft, revision, 0, 0)));
        for (int i = 1; i <= 5; i++) {
            int level = i;
            var limits = draft.level(level);
            if (level > 1) buttons.add(button(Items.GOLD_INGOT, "L" + level + " 升级费用: " + money(limits.upgradeCost()), p -> editConfig(p, draft, revision, level, 0)));
            buttons.add(button(Items.CHEST, "L" + level + " 商品种类上限: " + limits.listings(), p -> editConfig(p, draft, revision, level, 1)));
            buttons.add(button(Items.HOPPER, "L" + level + " 物品总容量: " + limits.capacity(), p -> editConfig(p, draft, revision, level, 2)));
        }
        buttons.add(button(Items.LIME_CONCRETE, "确认保存配置草稿", p -> {
            manager().updateConfig(p, draft, revision);
            openAdmin(p);
        }, "未点击保存前均为草稿", "限制须逐级不减；不能小于已有商品种类或库存", "不能通过减少上限删除任何物品"));
        buttons.add(button(Items.RED_CONCRETE, "放弃草稿 / 重新加载", PlayerShopGui::openAdmin));
        MenuGui.open(player, "电子商店管理员配置（草稿）", buttons, 0, PlayerShopGui::open);
    }
    private static void editConfig(ServerPlayer player, PlayerShopConfig draft, long revision, int level, int field) {
        if (!player.hasPermissions(2) || manager().configRevision() != revision) { openAdmin(player); return; }
        var limits = level == 0 ? draft.level(1) : draft.level(level);
        String initial = level == 0 ? String.valueOf(draft.openingCost) : field == 0 ? String.valueOf(limits.upgradeCost())
                : String.valueOf(field == 1 ? limits.listings() : limits.capacity());
        GuiInput.open(player, level == 0 ? "开店费用" : "L" + level + " " + (field == 0 ? "升级费用" : field == 1 ? "商品种类上限" : "物品总容量"), initial,
                () -> player.hasPermissions(2) && manager().configRevision() == revision, text -> {
                    if (!player.hasPermissions(2) || manager().configRevision() != revision) return;
                    if (field == 0) {
                        Double value = GuiInput.number(text, 0, ShopTradeRules.MAX_PRICE);
                        if (value != null && ShopTradeRules.price(value, true)) {
                            if (level == 0) draft.openingCost = value;
                            else draft.levels.set(level - 1, new PlayerShopConfig.Level(value, limits.listings(), limits.capacity()));
                        } else MessageUtil.sendError(player, "电子商店", "无效金额。");
                    } else {
                        Integer value = GuiInput.integer(text, 1, field == 1 ? 10000 : 1_000_000);
                        if (value != null) draft.levels.set(level - 1, new PlayerShopConfig.Level(limits.upgradeCost(),
                                field == 1 ? value : limits.listings(), field == 2 ? value : limits.capacity()));
                        else MessageUtil.sendError(player, "电子商店", "无效上限。");
                    }
                    openAdminDraft(player, draft, revision);
                }, () -> openAdminDraft(player, draft, revision));
    }
    private static void stale(ServerPlayer p) { MessageUtil.sendError(p, "电子商店", "内容或权限已变化，请重试。"); }
}
