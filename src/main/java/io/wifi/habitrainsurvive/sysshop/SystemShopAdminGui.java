package io.wifi.habitrainsurvive.sysshop;

import io.wifi.habitrainsurvive.menu.GuiInput;
import io.wifi.habitrainsurvive.economy.ExperienceCost;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Every accepted edit goes through a manager mutation which rechecks permission and object identity. */
public final class SystemShopAdminGui {
    private SystemShopAdminGui() { }
    private static SystemShopManager manager() { return SystemShopManager.get(); }

    public static void open(ServerPlayer player) {
        if (!check(player, null, null)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.EMERALD, "新建分类", SystemShopAdminGui::openCreateCategory,
                "直接输入中文或彩色显示名；内部ID自动生成"));
        buttons.add(button(Items.ITEM_FRAME, "新增商品", SystemShopAdminGui::openAddSelector,
                "选择分类、选择样品、设置价格后上架"));
        buttons.add(button(Items.CLOCK, "限购重置时区: " + manager().purchaseZone().getId(), p -> GuiInput.open(p,
                "时区（例如Asia/Shanghai）", manager().purchaseZone().getId(), () -> valid(p, null, null), value -> {
                    manager().setPurchaseTimeZone(p, value); open(p);
                }, () -> open(p)), "每日当地0点、每周一0点、每月1日0点刷新", "修改时区不会主动清除历史计数"));
        for (SystemShopCategory category : manager().getCategories().values()) {
            buttons.add(button(SystemShopGui.categoryIcon(category), category.displayName,
                    p -> openCategory(p, category, 0), "ID: " + category.id, "商品数: " + category.items.size(),
                    "分类动态价总开关: " + (category.dynamicEnabled ? "开" : "关")));
        }
        MenuGui.open(player, "系统商城管理", buttons, 0, SystemShopGui::openCategories);
    }

    public static void openCreateCategory(ServerPlayer player) {
        if (!check(player, null, null)) return;
        GuiInput.open(player, "新分类名称（支持中文）", "新分类", () -> valid(player, null, null), value -> {
            SystemShopCategory category = manager().createCategory(player, value);
            MessageUtil.sendSuccess(player, "系统商城", "分类已创建并保存，可在此添加商品。ID: " + category.id);
            openCategory(player, category, 0);
        }, () -> open(player));
    }

    public static void openAddSelector(ServerPlayer player) {
        if (!check(player, null, null)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.EMERALD, "先创建新分类", SystemShopAdminGui::openCreateCategory));
        for (SystemShopCategory category : manager().getCategories().values())
            buttons.add(button(SystemShopGui.categoryIcon(category), category.displayName, p -> openAddItem(p, category),
                    "ID: " + category.id, "点击向此分类添加商品"));
        MenuGui.open(player, "选择新增商品的分类", buttons, 0, SystemShopAdminGui::open);
    }

    public static void openAddItem(ServerPlayer player, SystemShopCategory category) {
        if (!check(player, category, null)) return;
        MenuGui.open(player, "添加商品 · " + category.displayName, List.of(
                button(Items.ITEM_FRAME, "添加主手物品", p -> add(p, category, p.getMainHandItem()), "复制完整组件，不消耗样品"),
                button(Items.CHEST, "从背包选择物品", p -> openInventory(p, category), "选择后设置买卖价格")), 0,
                p -> openCategory(p, category, 0));
    }

    public static void openCategory(ServerPlayer player, SystemShopCategory category, int page) {
        if (!check(player, category, null)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        buttons.add(button(Items.NAME_TAG, "分类名称: " + category.displayName, p -> GuiInput.open(p,
                "分类名称", category.displayName, () -> valid(p, category, null), text -> {
                    manager().editCategory(p, category, text, category.iconItemId);
                    openCategory(p, category, page);
                }, () -> openCategory(p, category, page))));
        buttons.add(button(SystemShopGui.categoryIcon(category), "分类图标", p -> GuiInput.open(p,
                "图标物品 ID", category.iconItemId, () -> valid(p, category, null), text -> {
                    manager().editCategory(p, category, category.displayName, text.trim());
                    openCategory(p, category, page);
                }, () -> openCategory(p, category, page)), category.iconItemId));
        buttons.add(button(Items.ITEM_FRAME, "添加主手物品", p -> attempt(p, () -> add(p, category, p.getMainHandItem())),
                "复制完整组件，不消耗物品", "填写购买/回收单价后创建，不再默认禁用买卖"));
        buttons.add(button(Items.CHEST, "从背包选择物品", p -> openInventory(p, category), "复制物品，不消耗"));
        buttons.add(button(Items.BARRIER, "删除整个分类", p -> confirm(p, "删除分类及其全部商品？",
                () -> valid(p, category, null), () -> {
                    manager().deleteCategory(p, category); open(p);
                }, () -> openCategory(p, category, page))));
        buttons.add(button(category.dynamicEnabled ? Items.LIME_DYE : Items.RED_DYE,
                "分类动态价格: " + (category.dynamicEnabled ? "开启" : "关闭"), p -> {
                    manager().setCategoryDynamic(p, category, !category.dynamicEnabled); openCategory(p, category, page);
                }, "关闭后全类按基准价，不受单品开启覆盖", "切换时重置本类供需压力，参数和限购计数保留"));
        buttons.add(button(Items.COMPARATOR, "分类购买限额", p -> SystemShopLimitsGui.open(p, category, null),
                "该分类全部商品合计；个人与全服共享同时生效", "单次 / 每日 / 每周 / 每月 / 累计，0不限"));
        for (SystemShopItem item : category.items) {
            int returnPage = buttons.size() / 45;
            buttons.add(new MenuGui.Button(SystemShopGui.itemIcon(player, item, item.displayName,
                    "基准购买: " + item.buyPrice + " / 回收: " + item.sellPrice,
                    "点击设置价格、上下限、灵敏度、恢复半衰期"), p -> openItem(p, category, item, returnPage)));
        }
        MenuGui.open(player, "管理分类 · " + category.displayName, buttons, page, SystemShopAdminGui::open);
    }

    private static void openInventory(ServerPlayer player, SystemShopCategory category) {
        if (!check(player, category, null)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (stack.isEmpty()) continue;
            int slot = i;
            ItemStack snapshot = stack.copy();
            buttons.add(new MenuGui.Button(MenuGui.icon(stack.getItem(), "槽位 " + (i + 1) + ": " + stack.getHoverName().getString(),
                    "复制完整物品组件，设置价格后上架"), p -> attempt(p, () -> {
                ItemStack live = p.getInventory().items.get(slot);
                if (!ItemStack.isSameItemSameComponents(live, snapshot))
                    throw new IllegalArgumentException("背包物品已变化，请重新选择");
                add(p, category, live);
            })));
        }
        MenuGui.open(player, "选择上架物品", buttons, 0, p -> openCategory(p, category, 0));
    }

    private static void add(ServerPlayer player, SystemShopCategory category, ItemStack stack) {
        if (!check(player, category, null)) return;
        if (stack.isEmpty()) throw new IllegalArgumentException("请先在主手拿上商品，或从背包选择非空样品");
        ItemStack sample = stack.copyWithCount(1);
        GuiInput.open(player, "购买单价（玩家付款，0禁用）", "10", () -> valid(player, category, null), buyText -> {
            double buy = SystemShopPricing.money(GuiInput.number(buyText, 0, SystemShopPricing.MAX_PRICE));
            GuiInput.open(player, "回收单价（系统付款，0禁用）", "0", () -> valid(player, category, null), sellText -> {
                double sell = SystemShopPricing.money(GuiInput.number(sellText, 0, SystemShopPricing.MAX_PRICE));
                if (buy == 0 && sell == 0) throw new IllegalArgumentException("购买和回收价格不能同时为0，至少开启一个交易方向");
                SystemShopItem item = manager().addItem(player, category, sample, buy, sell);
                MessageUtil.sendSuccess(player, "系统商城", "商品已上架并保存；样品未消耗。");
                openItem(player, category, item, 0);
            }, () -> openAddItem(player, category));
        }, () -> openAddItem(player, category));
    }

    public static void openItem(ServerPlayer player, SystemShopCategory category, SystemShopItem item, int page) {
        if (!check(player, category, item)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        double pressure = manager().dynamicEnabled(category, item) ? DynamicShopPrice.recovered(item, System.currentTimeMillis()) : 0;
        buttons.add(new MenuGui.Button(SystemShopGui.itemIcon(player, item, item.displayName,
                "当前倍率: " + String.format(java.util.Locale.ROOT, "%.4f", Math.exp(pressure)),
                "定价状态: " + manager().priceMode(category, item), "金额精确到分；0 基准价禁用该方向",
                "绝对上下限与百分比上下限同时生效", "调整基准前先扩大上下限，之后再收窄"), p -> { }));
        buttons.add(button(Items.NAME_TAG, "商品名称", p -> GuiInput.open(p, "商品名称", item.displayName,
                () -> valid(p, category, item), text -> {
                    manager().editItem(p, category, item, d -> d.displayName = text);
                    openItem(p, category, item, page);
                }, () -> openItem(p, category, item, page))));
        buttons.add(button(item.dynamicEnabled ? Items.LIME_DYE : Items.RED_DYE,
                "单品动态价格: " + (item.dynamicEnabled ? "开启" : "关闭"), p -> {
                    manager().editItem(p, category, item, draft -> draft.dynamicEnabled = !draft.dynamicEnabled); openItem(p, category, item, page);
                }, "分类总开关关闭时，本单品仍使用固定基准价", "切换时从基准价重新计价，不清除限购计数"));
        buttons.add(button(Items.EXPERIENCE_BOTTLE, "经验购买价格: " + item.experiencePrice.label(), p -> openExperiencePrice(p, category, item, page),
                "金币价与经验价可同时存在，玩家购买时选择", "支持按等级或按具体经验点", "经验价固定，不参与金币供需波动"));
        buttons.add(button(Items.COMPARATOR, "单品购买限额", p -> SystemShopLimitsGui.open(p, category, item),
                "个人与全服；日/周/月/累计等限制同时生效"));
        buttons.add(button(Items.HOPPER, "移动商品到其它分类", p -> moveItem(p, category, item),
                "保留商品ID、价格参数和单品购买计数"));
        number(buttons, category, item, page, "购买基准价", item.buyPrice, 0, SystemShopPricing.MAX_PRICE,
                (d, value) -> d.buyPrice = SystemShopPricing.money(value));
        number(buttons, category, item, page, "回收基准价", item.sellPrice, 0, SystemShopPricing.MAX_PRICE,
                (d, value) -> d.sellPrice = SystemShopPricing.money(value));
        number(buttons, category, item, page, "购买最低绝对价", item.pricing.minBuyPrice, 0, SystemShopPricing.MAX_PRICE,
                (d, value) -> d.pricing.minBuyPrice = value);
        number(buttons, category, item, page, "购买最高绝对价", item.pricing.maxBuyPrice, 0, SystemShopPricing.MAX_PRICE,
                (d, value) -> d.pricing.maxBuyPrice = value);
        number(buttons, category, item, page, "回收最低绝对价", item.pricing.minSellPrice, 0, SystemShopPricing.MAX_PRICE,
                (d, value) -> d.pricing.minSellPrice = value);
        number(buttons, category, item, page, "回收最高绝对价", item.pricing.maxSellPrice, 0, SystemShopPricing.MAX_PRICE,
                (d, value) -> d.pricing.maxSellPrice = value);
        number(buttons, category, item, page, "最低基准百分比", item.pricing.minMultiplier * 100, 0.0001, 100,
                (d, value) -> d.pricing.minMultiplier = value / 100);
        number(buttons, category, item, page, "最高基准百分比", item.pricing.maxMultiplier * 100, 100, 100000,
                (d, value) -> d.pricing.maxMultiplier = value / 100);
        number(buttons, category, item, page, "每批变化金额", item.pricing.changeAmount, 0, SystemShopPricing.MAX_PRICE,
                (d, value) -> d.pricing.changeAmount = SystemShopPricing.money(value));
        number(buttons, category, item, page, "每批变化百分比", item.pricing.changePercent, 0, 100,
                (d, value) -> d.pricing.changePercent = value);
        buttons.add(button(Items.COMPARATOR, "每批数量: " + item.pricing.sensitivityVolume, p -> GuiInput.open(p,
                "灵敏度数量", Integer.toString(item.pricing.sensitivityVolume), () -> valid(p, category, item), text -> {
                    int value = GuiInput.integer(text, 1, 1_000_000);
                    manager().editItem(p, category, item, d -> d.pricing.sensitivityVolume = value);
                    openItem(p, category, item, page);
                }, () -> openItem(p, category, item, page)), "默认64件；越小变动越快"));
        number(buttons, category, item, page, "恢复半衰期(小时)", item.pricing.halfLifeHours, 1.0 / 60, 87600,
                (d, value) -> d.pricing.halfLifeHours = value);
        buttons.add(button(Items.PAPER, "变化公式说明", p -> { },
                "每批 log 倍率变化 = ln(1 + 百分比/100 + 金额/参考基准价)",
                "参考基准价 = 购买基准价；禁用购买时取回收基准价",
                "每件分摊到每批数量；两个方向共用倍率", "金额是基准折算量，不是每笔固定加价",
                "默认每64件约1%，半衰期6小时；上下限50%..200%"));
        buttons.add(button(Items.MILK_BUCKET, "重置供需压力", p -> attempt(p, () -> {
            manager().resetPressure(p, category, item); openItem(p, category, item, page);
        }), "立即恢复至基准价格"));
        buttons.add(button(Items.CLOCK, "恢复默认动态参数", p -> confirm(p, "保留基准价格，恢复动态默认值？",
                () -> valid(p, category, item), () -> {
                    manager().editItem(p, category, item, d -> d.pricing = SystemShopPricing.defaults(d.buyPrice, d.sellPrice));
                    openItem(p, category, item, page);
                }, () -> openItem(p, category, item, page))));
        buttons.add(button(Items.BARRIER, "下架商品", p -> confirm(p, "确认下架商品？", () -> valid(p, category, item), () -> {
            manager().deleteItem(p, category, item); openCategory(p, category, page);
        }, () -> openItem(p, category, item, page))));
        MenuGui.open(player, "商品管理 · " + item.displayName, buttons, 0, p -> openCategory(p, category, page));
    }
    private static void openExperiencePrice(ServerPlayer player, SystemShopCategory category, SystemShopItem item, int page) {
        if (!check(player, category, item)) return;
        Runnable back = () -> openExperiencePrice(player, category, item, page);
        java.util.Map<Integer, MenuGui.Button> slots = new java.util.LinkedHashMap<>();
        slots.put(4, button(Items.EXPERIENCE_BOTTLE, "当前: " + item.experiencePrice.label(), p -> { },
                "金币购买仍可使用，二者互不替代", "经验购买共享每日/每周等限购"));
        slots.put(10, button(Items.RED_DYE, "关闭经验购买", p -> {
            manager().editItem(p, category, item, d -> d.experiencePrice = ExperienceCost.none()); openItem(p, category, item, page);
        }));
        slots.put(12, button(Items.ROTTEN_FLESH, "按等级定价", p -> GuiInput.open(p, "每个需要多少级经验", String.valueOf(Math.max(1, item.experiencePrice.amount)),
                () -> valid(p, category, item), value -> {
                    int amount = GuiInput.integer(value, 1, ExperienceCost.MAX_LEVELS);
                    manager().editItem(p, category, item, d -> d.experiencePrice = new ExperienceCost(ExperienceCost.LEVELS, amount));
                    openExperiencePrice(p, category, item, page);
                }, back), "扣除整级，保留当前经验条进度"));
        slots.put(14, button(Items.EXPERIENCE_BOTTLE, "按具体经验点定价", p -> GuiInput.open(p, "每个需要多少经验点", String.valueOf(Math.max(1, item.experiencePrice.amount)),
                () -> valid(p, category, item), value -> {
                    int amount = GuiInput.integer(value, 1, ExperienceCost.MAX_POINTS);
                    manager().editItem(p, category, item, d -> d.experiencePrice = new ExperienceCost(ExperienceCost.POINTS, amount));
                    openExperiencePrice(p, category, item, page);
                }, back), "按原版经验曲线精确扣除，自动回算等级"));
        slots.put(18, button(Items.ARROW, "返回商品管理", p -> openItem(p, category, item, page)));
        MenuGui.openSlots(player, "经验购买价格 · " + item.displayName, 3, slots);
    }

    private static void moveItem(ServerPlayer player, SystemShopCategory source, SystemShopItem item) {
        if (!check(player, source, item)) return;
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (SystemShopCategory target : manager().getCategories().values()) if (target != source)
            buttons.add(button(SystemShopGui.categoryIcon(target), target.displayName, p -> {
                manager().moveItem(p, source, item, target); openItem(p, target, item, 0);
            }, "移动后开始受目标分类的动态总开关和分类限额约束", "之前已使用的原分类额度不会返还"));
        MenuGui.open(player, "选择目标分类", buttons, 0, p -> openItem(p, source, item, 0));
    }

    private static void number(List<MenuGui.Button> buttons, SystemShopCategory category, SystemShopItem item, int page,
                               String title, double initial, double min, double max, BiConsumer<SystemShopItem, Double> setter) {
        buttons.add(button(Items.GOLD_NUGGET, title + ": " + initial, p -> GuiInput.open(p, title, Double.toString(initial),
                () -> valid(p, category, item), text -> {
                    double value = GuiInput.number(text, min, max);
                    manager().editItem(p, category, item, d -> setter.accept(d, value));
                    openItem(p, category, item, page);
                }, () -> openItem(p, category, item, page)), "点击输入具体数值；整份配置验证后立即保存"));
    }

    private static void confirm(ServerPlayer player, String title, BooleanSupplier valid, Runnable accepted, Runnable back) {
        if (!valid.getAsBoolean()) { MessageUtil.sendError(player, "系统商城", "权限或数据已变化，请重新打开管理界面"); return; }
        MenuGui.open(player, title, List.of(button(Items.LIME_CONCRETE, "确认", p -> attempt(p, () -> {
            if (!valid.getAsBoolean()) throw new IllegalArgumentException("权限或数据已变化");
            accepted.run();
        })), button(Items.RED_CONCRETE, "取消", p -> back.run())), 0, p -> back.run());
    }

    private static MenuGui.Button button(Item icon, String name, Consumer<ServerPlayer> action, String... lore) {
        return new MenuGui.Button(MenuGui.icon(icon, name, lore), p -> {
            if (!SystemShopManager.authorized(p)) { MessageUtil.sendError(p, "系统商城", "管理权限已失效"); p.closeContainer(); return; }
            attempt(p, () -> action.accept(p));
        });
    }

    private static boolean valid(ServerPlayer player, SystemShopCategory category, SystemShopItem item) {
        return SystemShopManager.authorized(player) && (category == null || manager().isCurrent(category))
                && (item == null || manager().isCurrent(category, item));
    }

    private static boolean check(ServerPlayer player, SystemShopCategory category, SystemShopItem item) {
        if (valid(player, category, item)) return true;
        MessageUtil.sendError(player, "系统商城", "缺少管理权限或数据已变化，请重新打开 /shop admin");
        return false;
    }

    private static void attempt(ServerPlayer player, Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException error) { MessageUtil.sendError(player, "系统商城", error.getMessage()); }
    }
}
