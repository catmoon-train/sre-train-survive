package io.wifi.habitrainsurvive.lottery;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

public class LotteryGui {
    public static void openPoolSelector(ServerPlayer player) {
        openPoolSelector(player, 0);
    }

    private static void openPoolSelector(ServerPlayer player, int requestedPage) {
        List<CratePool> pools = new ArrayList<>(LotteryManager.get().getPools().values());
        pools.sort(java.util.Comparator.comparing(pool -> pool.id));
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (pools.size() - 1) / 7)));
        pools = new ArrayList<>(pools.subList(page * 7, Math.min(pools.size(), (page + 1) * 7)));
        final List<CratePool> displayedPools = pools;
        SimpleContainer container = new SimpleContainer(27);
        ItemStack glass = new ItemStack(Items.PURPLE_STAINED_GLASS_PANE);
        glass.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < 27; i++) container.setItem(i, glass.copy());

        int[] displaySlots = {11, 13, 15, 10, 12, 14, 16};
        for (int i = 0; i < Math.min(displaySlots.length, pools.size()); i++) {
            CratePool pool = pools.get(i);
            ItemStack icon = new ItemStack(i == 0 ? Items.GOLD_BLOCK : (i == 1 ? Items.DIAMOND_BLOCK : Items.BEACON));
            icon.set(DataComponents.CUSTOM_NAME, Component.literal(pool.displayName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            int pity = LotteryManager.get().getPlayerPity(player.getUUID(), pool.id);
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("单抽金币: " + EconomyManager.get().format(pool.singleDrawCost)).withStyle(ChatFormatting.YELLOW),
                    Component.literal("经验单抽: " + (pool.experienceDrawCost != null ? pool.experienceDrawCost.label() : "未开放")).withStyle(ChatFormatting.AQUA),
                    Component.literal("十连九折: " + EconomyManager.get().format(pool.singleDrawCost * 9.0)).withStyle(ChatFormatting.AQUA),
                    Component.literal("当前保底进度: " + pity + " / " + pool.pityThreshold + " 抽必出稀有大奖").withStyle(ChatFormatting.LIGHT_PURPLE),
                    Component.literal("▶ 点击进入该祈愿池预览与抽奖").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
            )));
            container.setItem(displaySlots[i], icon);
        }
        if (page > 0) container.setItem(18, LotteryAdminGui.button(Items.ARROW, "上一页"));
        if ((page + 1) * 7 < LotteryManager.get().getPools().size()) {
            container.setItem(26, LotteryAdminGui.button(Items.ARROW, "下一页"));
        }
        if (player.hasPermissions(2)) {
            container.setItem(22, LotteryAdminGui.button(Items.COMMAND_BLOCK, "§c管理奖池", "新增奖池、编辑奖品、价格与保底"));
        }

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x3, syncId, inv, container, 3) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (clickType != ClickType.PICKUP || button != 0) return;
                if (slotId == 22 && sp.hasPermissions(2)) { LotteryAdminGui.open(sp); return; }
                if (slotId == 18 && page > 0) { openPoolSelector(sp, page - 1); return; }
                if (slotId == 26 && !container.getItem(26).isEmpty()
                        && container.getItem(26).is(Items.ARROW)) { openPoolSelector(sp, page + 1); return; }
                for (int i = 0; i < Math.min(displaySlots.length, displayedPools.size()); i++) {
                    if (slotId == displaySlots[i]) {
                        openPoolDetail(sp, displayedPools.get(i));
                        return;
                    }
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("🎰 幸运盲盒与祈愿神殿")));
    }

    public static void openPoolDetail(ServerPlayer player, CratePool pool) {
        openPoolDetail(player, pool, 0);
    }

    private static void openPoolDetail(ServerPlayer player, CratePool pool, int requestedPage) {
        if (LotteryManager.get().getPool(pool.id) != pool) { openPoolSelector(player); return; }
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (pool.prizes.size() - 1) / 36)));
        SimpleContainer container = new SimpleContainer(54);
        int totalWeight = Math.max(1, pool.getTotalWeight());

        for (int i = page * 36; i < Math.min((page + 1) * 36, pool.prizes.size()); i++) {
            CratePrize prize = pool.prizes.get(i);
            ItemStack icon = ItemSerializer.deserialize(prize.serializedItem, player.server.registryAccess()).copy();
            if (icon.isEmpty()) icon = new ItemStack(Items.SUNFLOWER);
            double prob = (prize.weight * 100.0) / totalWeight;
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal(String.format("掉落概率: %.2f%% (权重:%d)", prob, prize.weight)).withStyle(ChatFormatting.YELLOW));
            if (prize.bonusCoins > 0) {
                lore.add(Component.literal("额外附赠彩金: " + EconomyManager.get().format(prize.bonusCoins)).withStyle(ChatFormatting.GOLD));
            }
            if (prize.rareBroadcast) {
                lore.add(Component.literal("★ 稀有大奖 (触发全服金色广播 & 保底池)").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
            }
            icon.set(DataComponents.LORE, new ItemLore(lore));
            container.setItem(i % 36, icon);
        }

        ItemStack border = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        border.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int s = 36; s < 54; s++) container.setItem(s, border.copy());

        int pity = LotteryManager.get().getPlayerPity(player.getUUID(), pool.id);

        ItemStack backBtn = new ItemStack(Items.ARROW);
        backBtn.set(DataComponents.CUSTOM_NAME, Component.literal("« 返回奖池选择大厅").withStyle(ChatFormatting.YELLOW));
        container.setItem(45, backBtn);

        ItemStack draw1 = new ItemStack(Items.EMERALD);
        draw1.set(DataComponents.CUSTOM_NAME, Component.literal("✨ 祈愿单抽 (1次)").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        draw1.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("金币: " + EconomyManager.get().format(pool.singleDrawCost) + " (专属钥匙优先)").withStyle(ChatFormatting.YELLOW),
                Component.literal("经验: " + (pool.experienceDrawCost != null && pool.experienceDrawCost.enabled() ? pool.experienceDrawCost.label() : "未开放")).withStyle(ChatFormatting.AQUA),
                Component.literal("当前保底累计: " + pity + "/" + pool.pityThreshold).withStyle(ChatFormatting.AQUA)
        )));
        container.setItem(48, draw1);

        ItemStack draw10 = new ItemStack(Items.NETHER_STAR);
        draw10.set(DataComponents.CUSTOM_NAME, Component.literal("🔥 十连祈愿 (享9折特惠)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        draw10.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("金币九折: " + EconomyManager.get().format(pool.singleDrawCost * 9.0)).withStyle(ChatFormatting.YELLOW),
                Component.literal("经验九折: " + (pool.experienceDrawCost != null && pool.experienceDrawCost.enabled() ? pool.experienceDrawCost.label(9) : "未开放")).withStyle(ChatFormatting.AQUA),
                Component.literal("当前保底累计: " + pity + "/" + pool.pityThreshold).withStyle(ChatFormatting.AQUA)
        )));
        container.setItem(50, draw10);
        if (pool.experienceDrawCost != null && pool.experienceDrawCost.enabled()) {
            ItemStack expDraw1 = new ItemStack(Items.EXPERIENCE_BOTTLE);
            expDraw1.set(DataComponents.CUSTOM_NAME, Component.literal("✨ 经验单抽 (1次)").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            expDraw1.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("花费: " + pool.experienceDrawCost.label()).withStyle(ChatFormatting.YELLOW),
                    Component.literal("有专属钥匙时优先扣钥匙").withStyle(ChatFormatting.GRAY),
                    Component.literal("当前保底累计: " + pity + "/" + pool.pityThreshold).withStyle(ChatFormatting.LIGHT_PURPLE))));
            container.setItem(47, expDraw1);
            ItemStack expDraw10 = new ItemStack(Items.EXPERIENCE_BOTTLE);
            expDraw10.set(DataComponents.CUSTOM_NAME, Component.literal("🔥 经验十连 (九折)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            expDraw10.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("经验九折: " + pool.experienceDrawCost.label(9)).withStyle(ChatFormatting.YELLOW),
                    Component.literal("不会消耗金币").withStyle(ChatFormatting.GRAY),
                    Component.literal("当前保底累计: " + pity + "/" + pool.pityThreshold).withStyle(ChatFormatting.LIGHT_PURPLE))));
            container.setItem(51, expDraw10);
        }
        if (page > 0) container.setItem(46, LotteryAdminGui.button(Items.ARROW, "上一页奖品"));
        if ((page + 1) * 36 < pool.prizes.size()) container.setItem(52, LotteryAdminGui.button(Items.ARROW, "下一页奖品"));

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inv, container, 6) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (clickType != ClickType.PICKUP || button != 0) return;
                if (LotteryManager.get().getPool(pool.id) != pool) { openPoolSelector(sp); return; }
                if (slotId == 46 && page > 0) { openPoolDetail(sp, pool, page - 1); return; }
                if (slotId == 52 && (page + 1) * 36 < pool.prizes.size()) { openPoolDetail(sp, pool, page + 1); return; }
                if (slotId == 47 && pool.experienceDrawCost != null && pool.experienceDrawCost.enabled()) {
                    LotteryManager.get().performDrawWithExperience(sp, pool, 1); openPoolDetail(sp, pool, page); return;
                }
                if (slotId == 51 && pool.experienceDrawCost != null && pool.experienceDrawCost.enabled()) {
                    LotteryManager.get().performDrawWithExperience(sp, pool, 10); openPoolDetail(sp, pool, page); return;
                }
                if (slotId == 45) {
                    openPoolSelector(sp);
                } else if (slotId == 48) {
                    LotteryManager.get().performDraw(sp, pool, 1);
                    openPoolDetail(sp, pool, page);
                } else if (slotId == 50) {
                    LotteryManager.get().performDraw(sp, pool, 10);
                    openPoolDetail(sp, pool, page);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("祈愿池: " + pool.displayName)));
    }
}
