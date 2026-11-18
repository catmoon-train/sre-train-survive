package io.wifi.habitrainsurvive.lottery;

import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.economy.ExperienceCost;
import io.wifi.habitrainsurvive.menu.GuiInput;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class LotteryAdminGui {
    private LotteryAdminGui() {}

    public static void open(ServerPlayer player) {
        openPools(player, 0);
    }

    private static boolean authorized(ServerPlayer player) {
        if (player.hasPermissions(2)) return true;
        MessageUtil.sendError(player, "抽奖管理", "需要管理员权限。");
        player.closeContainer();
        return false;
    }

    private static boolean exists(CratePool pool) {
        return LotteryManager.get().getPool(pool.id) == pool;
    }

    private static boolean exists(CratePool pool, CratePrize prize) {
        return exists(pool) && pool.prizes.contains(prize);
    }

    static ItemStack button(Item item, String name, String... lines) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(lines)
                .map(line -> (Component) Component.literal("§7" + line)).toList()));
        return stack;
    }

    private static final class Page {
        final SimpleContainer container = new SimpleContainer(54);
        final Map<Integer, Runnable> actions = new HashMap<>();

        void put(int slot, ItemStack icon, Runnable action) {
            container.setItem(slot, icon);
            actions.put(slot, action);
        }

        void show(ServerPlayer player, String title, BooleanSupplier valid, Consumer<ItemStack> inventoryClick) {
            if (!authorized(player)) return;
            player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                    new ChestMenu(MenuType.GENERIC_9x6, id, inventory, container, 6) {
                        @Override
                        public void clicked(int slot, int mouseButton, ClickType type, Player clicker) {
                            if (!(clicker instanceof ServerPlayer sp) || !authorized(sp)) return;
                            if (!valid.getAsBoolean()) {
                                MessageUtil.sendError(sp, "抽奖管理", "数据已被其他管理员删除，请重新选择。");
                                open(sp);
                                return;
                            }
                            if (type != ClickType.PICKUP || mouseButton != 0) return;
                            if (slot >= 54 && slot < slots.size() && inventoryClick != null) {
                                ItemStack selected = getSlot(slot).getItem();
                                if (!selected.isEmpty()) inventoryClick.accept(selected.copy());
                                return;
                            }
                            Runnable action = actions.get(slot);
                            if (action != null) action.run();
                        }

                        @Override
                        public ItemStack quickMoveStack(Player p, int index) { return ItemStack.EMPTY; }
                    }, Component.literal(title)));
        }
    }

    private static void openPools(ServerPlayer player, int requestedPage) {
        List<CratePool> pools = new ArrayList<>(LotteryManager.get().getPools().values());
        pools.sort(Comparator.comparing(pool -> pool.id));
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (pools.size() - 1) / 45)));
        Page menu = new Page();
        for (int i = page * 45; i < Math.min(pools.size(), (page + 1) * 45); i++) {
            CratePool pool = pools.get(i);
            menu.put(i % 45, button(Items.GOLD_BLOCK, pool.displayName, "ID: " + pool.id,
                    "单抽金币: " + pool.singleDrawCost + " / 保底: " + pool.pityThreshold,
                    "经验单抽: " + (pool.experienceDrawCost != null ? pool.experienceDrawCost.label() : "未开放"),
                    "奖品数: " + pool.prizes.size(), "点击编辑"), () -> openPool(player, pool, 0));
        }
        menu.put(45, button(Items.ARROW, "§e返回抽奖大厅"), () -> LotteryGui.openPoolSelector(player));
        if (page > 0) menu.put(46, button(Items.PAPER, "上一页"), () -> openPools(player, page - 1));
        menu.put(49, button(Items.EMERALD, "§a新增奖池", "输入唯一ID，再设置名称、价格与奖品"), () ->
                input(player, "新增奖池：输入ID", "new_pool", () -> true, value -> {
                    String id = LotteryEditValues.poolId(value);
                    CratePool pool = new CratePool(id, id, 200, 50);
                    if (LotteryManager.get().getPools().putIfAbsent(id, pool) != null) {
                        throw new IllegalArgumentException("这个奖池ID已存在");
                    }
                    LotteryManager.get().saveChanges();
                    openPool(player, pool, 0);
                }, () -> openPools(player, page)));
        if ((page + 1) * 45 < pools.size()) menu.put(52, button(Items.PAPER, "下一页"), () -> openPools(player, page + 1));
        menu.show(player, "抽奖管理 · 奖池 " + (page + 1), () -> true, null);
    }

    private static void openPool(ServerPlayer player, CratePool pool, int requestedPage) {
        if (!exists(pool)) { open(player); return; }
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (pool.prizes.size() - 1) / 36)));
        Page menu = new Page();
        List<CratePrize> prizes = new ArrayList<>(pool.prizes);
        for (int i = page * 36; i < Math.min(prizes.size(), (page + 1) * 36); i++) {
            CratePrize prize = prizes.get(i);
            ItemStack icon = ItemSerializer.deserialize(prize.serializedItem, player.server.registryAccess());
            if (icon.isEmpty()) icon = new ItemStack(Items.BARRIER);
            // Only the preview copy receives management lore; stored item components stay intact.
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("§e权重: " + prize.weight + " / 彩金: " + prize.bonusCoins),
                    Component.literal("§d稀有与保底: " + prize.rareBroadcast),
                    Component.literal("§a点击编辑物品与抽奖参数"))));
            menu.put(i % 36, icon, () -> openPrize(player, pool, prize, page));
        }
        Runnable back = () -> openPool(player, pool, page);
        menu.put(36, button(Items.NAME_TAG, "§e修改奖池名称", pool.displayName), () ->
                input(player, "奖池名称（支持&颜色）", pool.displayName, () -> exists(pool), value -> {
                    pool.displayName = LotteryEditValues.name(value); save(back);
                }, back));
        menu.put(38, button(Items.GOLD_INGOT, "§e修改单抽价格", String.valueOf(pool.singleDrawCost), "十连自动按单抽价格的9倍收费"), () ->
                input(player, "单抽价格", String.valueOf(pool.singleDrawCost), () -> exists(pool), value -> {
                    pool.singleDrawCost = LotteryEditValues.coins(value); save(back);
                }, back));
        menu.put(40, button(Items.NETHER_STAR, "§e修改保底次数", String.valueOf(pool.pityThreshold), "保底从开启稀有标记的奖品中等概率抽取"), () ->
                input(player, "保底次数", String.valueOf(pool.pityThreshold), () -> exists(pool), value -> {
                    pool.pityThreshold = LotteryEditValues.integer(value, 1_000_000); save(back);
                }, back));
        menu.put(42, button(Items.GOLD_BLOCK, "§a添加奖品", "点击后，从下方背包选择物品", "复制完整物品数据，不消耗原物品"), () ->
                selectItem(player, pool, "选择要添加的奖品", back, stack -> {
                    CratePrize prize = new CratePrize(ItemSerializer.serialize(stack, player.server.registryAccess()),
                            stack.getHoverName().getString(), stack.getCount(), 0, 10, false);
                    pool.prizes.add(prize);
                    LotteryManager.get().saveChanges();
                    openPrize(player, pool, prize, page);
                }));
        menu.put(44, button(Items.BARRIER, "§c删除奖池", "将删除该池全部奖品与玩家保底进度"), () ->
                confirm(player, "确认删除奖池 " + pool.id, () -> exists(pool), () -> {
                    LotteryManager.get().deletePool(pool); open(player);
                }, back));
        menu.put(43, button(Items.EXPERIENCE_BOTTLE, "§b经验抽奖价格: " + pool.experienceDrawCost.label(),
                "金币抽奖与经验抽奖并存", "可为每个奖池选择按等级或具体经验点"), () -> openExperiencePrice(player, pool, page));
        menu.put(45, button(Items.ARROW, "返回奖池列表"), () -> openPools(player, 0));
        if (page > 0) menu.put(46, button(Items.PAPER, "上一页"), () -> openPool(player, pool, page - 1));
        if ((page + 1) * 36 < prizes.size()) menu.put(52, button(Items.PAPER, "下一页"), () -> openPool(player, pool, page + 1));
        menu.show(player, "编辑奖池 · " + pool.displayName + " · " + (page + 1), () -> exists(pool), null);
    }
    private static void openExperiencePrice(ServerPlayer player, CratePool pool, int page) {
        if (!authorized(player) || !exists(pool)) return;
        Page menu = new Page();
        menu.put(4, button(Items.EXPERIENCE_BOTTLE, "当前经验单抽: " + pool.experienceDrawCost.label(),
                "十连按单抽经验价的9倍", "经验价格不参与金币动态价格"), () -> { });
        menu.put(10, button(Items.RED_DYE, "§c关闭经验抽奖", "只保留金币抽奖"), () -> {
            pool.experienceDrawCost = ExperienceCost.none(); LotteryManager.get().saveChanges();
            openPool(player, pool, page);
        });
        menu.put(12, button(Items.ROTTEN_FLESH, "§a按等级定价", "扣除整级，保留经验条进度"), () ->
                GuiInput.open(player, "单抽需要多少级经验", String.valueOf(Math.max(1, pool.experienceDrawCost.amount)),
                        () -> authorized(player) && exists(pool), value -> {
                            int amount = GuiInput.integer(value, 1, ExperienceCost.MAX_LEVELS);
                            pool.experienceDrawCost = new ExperienceCost(ExperienceCost.LEVELS, amount);
                            LotteryManager.get().saveChanges(); openPool(player, pool, page);
                        }, () -> openExperiencePrice(player, pool, page)));
        menu.put(14, button(Items.EXPERIENCE_BOTTLE, "§b按具体经验点定价", "按原版经验曲线精确扣除并回算等级"), () ->
                GuiInput.open(player, "单抽需要多少经验点", String.valueOf(Math.max(1, pool.experienceDrawCost.amount)),
                        () -> authorized(player) && exists(pool), value -> {
                            int amount = GuiInput.integer(value, 1, ExperienceCost.MAX_POINTS);
                            pool.experienceDrawCost = new ExperienceCost(ExperienceCost.POINTS, amount);
                            LotteryManager.get().saveChanges(); openPool(player, pool, page);
                        }, () -> openExperiencePrice(player, pool, page)));
        menu.put(18, button(Items.ARROW, "返回奖池编辑"), () -> openPool(player, pool, page));
        menu.show(player, "经验抽奖价格 · " + pool.displayName, () -> exists(pool), null);
    }

    private static void openPrize(ServerPlayer player, CratePool pool, CratePrize prize, int page) {
        if (!exists(pool, prize)) { open(player); return; }
        Page menu = new Page();
        ItemStack original = ItemSerializer.deserialize(prize.serializedItem, player.server.registryAccess());
        menu.container.setItem(13, original.isEmpty() ? new ItemStack(Items.BARRIER) : original.copy());
        Runnable back = () -> openPrize(player, pool, prize, page);
        BooleanSupplier valid = () -> exists(pool, prize);
        menu.put(19, button(Items.GOLD_BLOCK, "§a替换奖品物品", "从背包复制物品、数量及全部组件", "保留原权重、彩金和稀有设置"), () ->
                selectItem(player, pool, "选择替换物品", back, stack -> {
                    if (!valid.getAsBoolean()) { open(player); return; }
                    updateItem(player, prize, stack); save(back);
                }));
        menu.put(21, button(Items.NAME_TAG, "§e修改物品名称", prize.displayName), () ->
                input(player, "物品名称（支持&颜色）", prize.displayName, valid, value -> {
                    String name = LotteryEditValues.name(value);
                    ItemStack stack = requireItem(player, prize);
                    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
                    updateItem(player, prize, stack); save(back);
                }, back));
        menu.put(23, button(Items.PAPER, "§e修改物品数量", String.valueOf(prize.count)), () ->
                input(player, "物品数量", String.valueOf(prize.count), valid, value -> {
                    ItemStack stack = requireItem(player, prize);
                    stack.setCount(LotteryEditValues.integer(value, stack.getMaxStackSize()));
                    updateItem(player, prize, stack); save(back);
                }, back));
        menu.put(28, button(Items.REDSTONE, "§e修改抽中权重", String.valueOf(prize.weight), "普通抽奖概率 = 权重 / 总权重"), () ->
                input(player, "奖品权重", String.valueOf(prize.weight), valid, value -> {
                    int weight = LotteryEditValues.integer(value, 10000);
                    long total = pool.prizes.stream().mapToLong(p -> Math.max(1, p.weight)).sum() - Math.max(1, prize.weight) + weight;
                    if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("奖池总权重过大");
                    prize.weight = weight; save(back);
                }, back));
        menu.put(30, button(Items.GOLD_INGOT, "§e修改附赠金币", String.valueOf(prize.bonusCoins)), () ->
                input(player, "附赠金币", String.valueOf(prize.bonusCoins), valid, value -> {
                    prize.bonusCoins = LotteryEditValues.coins(value); save(back);
                }, back));
        menu.put(32, button(Items.NETHER_STAR, "§d稀有大奖：" + (prize.rareBroadcast ? "开启" : "关闭"),
                "点击切换：全服播报、参与保底、抽中重置保底"), () -> {
            prize.rareBroadcast = !prize.rareBroadcast; save(back);
        });
        menu.put(45, button(Items.ARROW, "返回奖品列表"), () -> openPool(player, pool, page));
        menu.put(49, button(Items.BARRIER, "§c删除此奖品"), () -> confirm(player, "确认删除此奖品", valid, () -> {
            pool.prizes.remove(prize); save(() -> openPool(player, pool, page));
        }, back));
        menu.show(player, "编辑奖品 · " + prize.displayName, valid, null);
    }

    private static ItemStack requireItem(ServerPlayer player, CratePrize prize) {
        ItemStack stack = ItemSerializer.deserialize(prize.serializedItem, player.server.registryAccess());
        if (stack.isEmpty()) throw new IllegalArgumentException("奖品物品无效，请先从背包替换物品");
        return stack;
    }

    private static void updateItem(ServerPlayer player, CratePrize prize, ItemStack stack) {
        prize.serializedItem = ItemSerializer.serialize(stack, player.server.registryAccess());
        prize.displayName = stack.getHoverName().getString();
        prize.count = stack.getCount();
    }

    private static void save(Runnable next) {
        LotteryManager.get().saveChanges();
        next.run();
    }

    private static void selectItem(ServerPlayer player, CratePool pool, String title, Runnable back, Consumer<ItemStack> selected) {
        Page menu = new Page();
        menu.container.setItem(22, button(Items.GOLD_BLOCK, "§a点击下方背包中的物品", "不移动或消耗原物品", "附魔、自定义名称、Lore与其他组件完整保留"));
        menu.put(45, button(Items.ARROW, "取消并返回"), back);
        menu.show(player, title, () -> exists(pool), selected);
    }

    private static void confirm(ServerPlayer player, String title, BooleanSupplier valid, Runnable confirmed, Runnable back) {
        Page menu = new Page();
        menu.put(20, button(Items.RED_CONCRETE, "§c确认删除", "不可撤销"), confirmed);
        menu.put(24, button(Items.LIME_CONCRETE, "§a取消"), back);
        menu.show(player, title, valid, null);
    }

    private static void input(ServerPlayer player, String title, String initial, BooleanSupplier valid,
                              Consumer<String> accepted, Runnable back) {
        if (!authorized(player)) return;
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new AnvilMenu(id, inventory,
                ContainerLevelAccess.create(player.level(), player.blockPosition())) {
            private String value = initial;

            @Override
            public boolean stillValid(Player p) { return true; }

            @Override
            public boolean setItemName(String name) {
                value = name;
                createResult();
                return true;
            }

            @Override
            public void createResult() {
                getSlot(2).set(button(Items.LIME_DYE, "§a确认输入", "点击保存；输入无效会在聊天栏提示"));
                setData(0, 0);
                broadcastChanges();
            }

            @Override
            protected boolean mayPickup(Player p, boolean hasItem) { return true; }

            @Override
            public void clicked(int slot, int mouseButton, ClickType type, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp) || !authorized(sp)) return;
                if (!valid.getAsBoolean()) { open(sp); return; }
                if (type != ClickType.PICKUP || mouseButton != 0) return;
                if (slot == 0) { back.run(); return; }
                if (slot != 2) return;
                try {
                    accepted.accept(value);
                } catch (IllegalArgumentException exception) {
                    MessageUtil.sendError(sp, "抽奖管理", exception.getMessage());
                }
            }

            @Override
            public ItemStack quickMoveStack(Player p, int index) { return ItemStack.EMPTY; }

            @Override
            public void removed(Player p) {
                // Input/output are UI-only items. Never return them to the player's inventory.
                getSlot(0).set(ItemStack.EMPTY);
                getSlot(1).set(ItemStack.EMPTY);
                getSlot(2).set(ItemStack.EMPTY);
                super.removed(p);
            }
        }, Component.literal(title)));
        if (player.containerMenu instanceof AnvilMenu menu) {
            menu.getSlot(0).set(button(Items.PAPER, initial, "在上方输入框编辑，点击右侧确认", "点击左侧纸张取消；不消耗经验或物品"));
            menu.setItemName(initial);
            menu.getSlot(2).set(button(Items.LIME_DYE, "§a确认输入", "点击保存；输入无效会在聊天栏提示"));
            menu.broadcastChanges();
        }
    }
}
