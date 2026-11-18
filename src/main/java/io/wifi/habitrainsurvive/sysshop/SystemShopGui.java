package io.wifi.habitrainsurvive.sysshop;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.economy.ExperienceCost;
import io.wifi.habitrainsurvive.economy.ExperiencePayment;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** All display slots use MenuGui's frozen inventory; trades are explicit quantity buttons. */
public final class SystemShopGui {
    private SystemShopGui() { }

    public static void openCategories(ServerPlayer player) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (SystemShopCategory category : SystemShopManager.get().getCategories().values()) {
            buttons.add(new MenuGui.Button(MenuGui.icon(categoryIcon(category), category.displayName,
                    category.items.size() + " 件商品", "分类动态价总开关: " + (category.dynamicEnabled ? "开" : "关"), "点击浏览"), p -> openCategory(p, category, 0)));
        }
        if (SystemShopManager.authorized(player)) buttons.add(new MenuGui.Button(
                MenuGui.icon(Items.COMMAND_BLOCK, "商城管理"), SystemShopAdminGui::open));
        MenuGui.open(player, "系统商城 · 全部分类", buttons, 0, null);
    }

    public static void openCategoryView(ServerPlayer player, String categoryId) {
        var manager = SystemShopManager.get();
        SystemShopCategory category = manager.getCategory(categoryId);
        if (category == null) category = manager.getCategories().values().stream().findFirst().orElse(null);
        if (category == null) openCategories(player);
        else openCategory(player, category, 0);
    }

    private static void openCategory(ServerPlayer player, SystemShopCategory category, int page) {
        var manager = SystemShopManager.get();
        if (!manager.isCurrent(category)) { openCategories(player); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(new MenuGui.Button(MenuGui.icon(Items.CHEST, "切换分类", wallet(player)), SystemShopGui::openCategories));
        if (SystemShopManager.authorized(player)) {
            buttons.add(new MenuGui.Button(MenuGui.icon(Items.COMMAND_BLOCK, "商城管理", "新建分类、编辑和添加商品"), SystemShopAdminGui::open));
            buttons.add(new MenuGui.Button(MenuGui.icon(Items.ITEM_FRAME, "新增商品", "向当前分类添加主手/背包样品"), p -> SystemShopAdminGui.openAddItem(p, category)));
        }
        for (SystemShopItem item : category.items) {
            int returnPage = buttons.size() / 45;
            ItemStack icon = itemIcon(player, item, item.displayName,
                    "购买 1 个: " + quoteLabel(item, true, 1), "回收 1 个: " + quoteLabel(item, false, 1),
                    "定价: " + manager.priceMode(category, item), "点击查看购买数量与个人/全服剩余额度");
            buttons.add(new MenuGui.Button(icon, p -> openTrade(p, category, item, returnPage)));
        }
        MenuGui.open(player, "系统商城 · " + category.displayName, buttons, page, SystemShopGui::openCategories);
    }

    private static void openTrade(ServerPlayer player, SystemShopCategory category, SystemShopItem item, int page) {
        var manager = SystemShopManager.get();
        if (!manager.isCurrent(category, item)) { openCategoryView(player, category.id); return; }
        ItemStack template = ItemSerializer.deserialize(item.serializedItem, player.server.registryAccess());
        int stack = template.isEmpty() ? 64 : template.getMaxStackSize();
        int available = manager.countSellable(player, item);
        List<MenuGui.Button> buttons = new ArrayList<>();
        ExperienceCost exp = item.experiencePrice == null ? ExperienceCost.none() : item.experiencePrice;
        buttons.add(new MenuGui.Button(itemIcon(player, item, item.displayName, wallet(player),
                "背包可回收: " + available, "经验: " + ExperiencePayment.summary(player),
                "金币购买: " + (item.buyPrice > 0 ? quoteLabel(item, true, 1) : "未开放"),
                "经验购买: " + (exp.enabled() ? exp.label() + " / 个" : "未开放"),
                "组件必须完全一致，不含盔甲/副手", "报价与余额在成交前实时重新检查"), p -> { }));
        if (item.buyPrice > 0) {
            buttons.add(tradeButton(item, true, 1, "金币购买 1 个", p -> {
                manager.buyItem(p, item, 1); openTrade(p, category, item, page);
            }));
            buttons.add(tradeButton(item, true, stack, "金币购买一组 (" + stack + ")", p -> {
                manager.buyItem(p, item, stack); openTrade(p, category, item, page);
            }));
        }
        if (exp.enabled()) {
            buttons.add(experienceButton(exp, 1, "经验购买 1 个", p -> {
                manager.buyItemWithExperience(p, item, 1); openTrade(p, category, item, page);
            }));
            buttons.add(experienceButton(exp, stack, "经验购买一组 (" + stack + ")", p -> {
                manager.buyItemWithExperience(p, item, stack); openTrade(p, category, item, page);
            }));
        }
        if (item.sellPrice > 0) {
            buttons.add(tradeButton(item, false, 1, "出售 1 个", p -> {
                manager.sellItem(p, item, false); openTrade(p, category, item, page);
            }));
            buttons.add(tradeButton(item, false, available, "出售背包全部 (" + available + ")", p -> {
                manager.sellItem(p, item, true); openTrade(p, category, item, page);
            }));
        }
        buttons.add(new MenuGui.Button(MenuGui.icon(Items.CLOCK, "刷新报价"), p -> openTrade(p, category, item, page)));
        long now = System.currentTimeMillis();
        List<String> quota = manager.purchaseQuotaLore(player, category, item, now);
        buttons.add(new MenuGui.Button(MenuGui.icon(Items.COMPARATOR, "购买限额 · 剩余件数", quota.toArray(String[]::new)), p -> openTrade(p, category, item, page)));
        int allowed = manager.maxPurchasable(player, category, item, stack, now);
        if (allowed > 0 && allowed < stack) {
            if (item.buyPrice > 0) buttons.add(tradeButton(item, true, allowed, "金币购买当前可用数量 (" + allowed + ")", p -> {
                manager.buyItem(p, item, allowed); openTrade(p, category, item, page);
            }));
            if (exp.enabled()) buttons.add(experienceButton(exp, allowed, "经验购买当前可用数量 (" + allowed + ")", p -> {
                manager.buyItemWithExperience(p, item, allowed); openTrade(p, category, item, page);
            }));
        }
        MenuGui.open(player, "交易 · " + item.displayName, buttons, 0, p -> openCategory(p, category, page));
    }

    private static MenuGui.Button tradeButton(SystemShopItem item, boolean buy, int count, String title,
                                               java.util.function.Consumer<ServerPlayer> action) {
        return new MenuGui.Button(MenuGui.icon(buy ? Items.EMERALD : Items.GOLD_INGOT, title,
                "合计: " + quoteLabel(item, buy, count), "成交前重新检查价格、限购、余额与背包空间"), action);
    }

    private static MenuGui.Button experienceButton(ExperienceCost cost, int count, String title,
                                                     java.util.function.Consumer<ServerPlayer> action) {
        return new MenuGui.Button(MenuGui.icon(Items.EXPERIENCE_BOTTLE, title,
                "合计: " + cost.label(count), "经验价格固定，不随金币供需波动",
                "等级模式保留当前经验条进度；点数模式按原版经验曲线扣除",
                "成交前重新检查经验、限购和背包空间"), action);
    }

    private static String quoteLabel(SystemShopItem item, boolean buy, int count) {
        if (count == 0) return "无可售物品";
        try {
            return EconomyManager.get().format(SystemShopManager.get().quote(item, buy, count, System.currentTimeMillis()).total());
        } catch (IllegalArgumentException invalid) {
            return "不支持 / 配置无效";
        }
    }

    static Item categoryIcon(SystemShopCategory category) {
        ResourceLocation id = ResourceLocation.tryParse(category.iconItemId);
        Item icon = id == null ? Items.CHEST : BuiltInRegistries.ITEM.get(id);
        return icon == Items.AIR ? Items.CHEST : icon;
    }

    static ItemStack itemIcon(ServerPlayer player, SystemShopItem item, String name, String... lore) {
        ItemStack icon = ItemSerializer.deserialize(item.serializedItem, player.server.registryAccess()).copyWithCount(1);
        if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);
        icon.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        icon.set(DataComponents.LORE, new ItemLore(Arrays.stream(lore).map(line -> (Component) Component.literal(line)).toList()));
        return icon;
    }

    private static String wallet(ServerPlayer player) {
        return "钱包: " + EconomyManager.get().format(EconomyManager.get().getBalance(player.getUUID()));
    }
}
