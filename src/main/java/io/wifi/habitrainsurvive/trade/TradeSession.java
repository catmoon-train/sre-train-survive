package io.wifi.habitrainsurvive.trade;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TradeSession {
    private static final Set<Integer> SLOTS_A = Set.of(0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21);
    private static final Set<Integer> SLOTS_B = Set.of(5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26);

    private final ServerPlayer playerA;
    private final ServerPlayer playerB;
    private final SimpleContainer container = new SimpleContainer(54);
    private double moneyOfferA = 0.0;
    private double moneyOfferB = 0.0;
    private boolean readyA = false;
    private boolean readyB = false;
    private boolean confirmedA = false;
    private boolean confirmedB = false;
    private boolean completedOrClosed = false;

    public TradeSession(ServerPlayer playerA, ServerPlayer playerB) {
        this.playerA = playerA;
        this.playerB = playerB;
        refreshDecorations();
    }

    public void openMenus() {
        playerA.openMenu(new SimpleMenuProvider(
                (syncId, inv, p) -> new TradeGuiMenu(syncId, inv, true),
                Component.literal("面对面安全交易: " + playerA.getName().getString() + " ⇄ " + playerB.getName().getString())
        ));
        playerB.openMenu(new SimpleMenuProvider(
                (syncId, inv, p) -> new TradeGuiMenu(syncId, inv, false),
                Component.literal("面对面安全交易: " + playerA.getName().getString() + " ⇄ " + playerB.getName().getString())
        ));
    }

    private static ItemStack makeIcon(net.minecraft.world.item.Item item, Component title, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, title);
        if (lore != null && !lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }

    private void refreshDecorations() {
        ItemStack divider = makeIcon(Items.GRAY_STAINED_GLASS_PANE, Component.literal("│ 交易分隔线 │").withStyle(ChatFormatting.DARK_GRAY), List.of());
        for (int row = 0; row < 6; row++) {
            container.setItem(row * 9 + 4, divider.copy());
        }
        for (int s = 27; s < 36; s++) {
            if (s != 31) {
                container.setItem(s, divider.copy());
            }
        }
        // 金币报价展示
        container.setItem(31, makeIcon(Items.GOLD_BLOCK,
                Component.literal("双方金币报价详情").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                List.of(
                        Component.literal(playerA.getName().getString() + " 出价: " + EconomyManager.get().format(moneyOfferA)).withStyle(ChatFormatting.YELLOW),
                        Component.literal(playerB.getName().getString() + " 出价: " + EconomyManager.get().format(moneyOfferB)).withStyle(ChatFormatting.AQUA)
                )));

        // A侧加钱按钮 (36, 37, 38, 39)
        container.setItem(36, makeIcon(Items.GOLD_NUGGET, Component.literal("[左侧] +10 金币").withStyle(ChatFormatting.YELLOW), List.of()));
        container.setItem(37, makeIcon(Items.GOLD_INGOT, Component.literal("[左侧] +100 金币").withStyle(ChatFormatting.GOLD), List.of()));
        container.setItem(38, makeIcon(Items.EMERALD, Component.literal("[左侧] +1000 金币").withStyle(ChatFormatting.GREEN), List.of()));
        container.setItem(39, makeIcon(Items.BARRIER, Component.literal("[左侧] 清零金币出价").withStyle(ChatFormatting.RED), List.of()));

        // B侧加钱按钮 (41, 42, 43, 44)
        container.setItem(41, makeIcon(Items.GOLD_NUGGET, Component.literal("[右侧] +10 金币").withStyle(ChatFormatting.YELLOW), List.of()));
        container.setItem(42, makeIcon(Items.GOLD_INGOT, Component.literal("[右侧] +100 金币").withStyle(ChatFormatting.GOLD), List.of()));
        container.setItem(43, makeIcon(Items.EMERALD, Component.literal("[右侧] +1000 金币").withStyle(ChatFormatting.GREEN), List.of()));
        container.setItem(44, makeIcon(Items.BARRIER, Component.literal("[右侧] 清零金币出价").withStyle(ChatFormatting.RED), List.of()));

        // A 锁定与确认 (45, 46)
        container.setItem(45, makeIcon(readyA ? Items.LIME_DYE : Items.GRAY_DYE,
                Component.literal(playerA.getName().getString() + (readyA ? " [已锁定报价]" : " [点击锁定报价]")).withStyle(readyA ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                List.of(Component.literal("修改任何物品或金额会自动解除双方锁定").withStyle(ChatFormatting.YELLOW))));
        container.setItem(46, makeIcon(confirmedA ? Items.EMERALD_BLOCK : Items.REDSTONE_BLOCK,
                Component.literal(playerA.getName().getString() + (confirmedA ? " [已最终确认]" : " [需双方锁定后点击确认]")).withStyle(confirmedA ? ChatFormatting.GREEN : ChatFormatting.RED),
                List.of()));

        // B 锁定与确认 (53, 52)
        container.setItem(53, makeIcon(readyB ? Items.LIME_DYE : Items.GRAY_DYE,
                Component.literal(playerB.getName().getString() + (readyB ? " [已锁定报价]" : " [点击锁定报价]")).withStyle(readyB ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                List.of(Component.literal("修改任何物品或金额会自动解除双方锁定").withStyle(ChatFormatting.YELLOW))));
        container.setItem(52, makeIcon(confirmedB ? Items.EMERALD_BLOCK : Items.REDSTONE_BLOCK,
                Component.literal(playerB.getName().getString() + (confirmedB ? " [已最终确认]" : " [需双方锁定后点击确认]")).withStyle(confirmedB ? ChatFormatting.GREEN : ChatFormatting.RED),
                List.of()));

        // 中央取消按钮 (49)
        container.setItem(49, makeIcon(Items.TNT, Component.literal("取消交易并退回物品").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), List.of()));
    }

    private void resetReadyStates() {
        readyA = false;
        readyB = false;
        confirmedA = false;
        confirmedB = false;
        refreshDecorations();
    }

    private void adjustMoney(boolean isPlayerA, double delta, boolean clear) {
        ServerPlayer actor = isPlayerA ? playerA : playerB;
        double current = isPlayerA ? moneyOfferA : moneyOfferB;
        double next = clear ? 0.0 : current + delta;
        if (!EconomyManager.get().hasBalance(actor.getUUID(), next)) {
            MessageUtil.sendError(actor, "交易", "您的账户余额不足以出价 " + EconomyManager.get().format(next));
            MessageUtil.playError(actor);
            return;
        }
        if (isPlayerA) {
            moneyOfferA = next;
        } else {
            moneyOfferB = next;
        }
        resetReadyStates();
        MessageUtil.playDing(actor);
    }

    private synchronized void tryCompleteTrade() {
        if (completedOrClosed || !readyA || !readyB || !confirmedA || !confirmedB) return;
        if (!EconomyManager.get().hasBalance(playerA.getUUID(), moneyOfferA)) {
            MessageUtil.sendError(playerA, "交易", "您的金币余额不足，交易中止！");
            cancelTrade("一方金币余额不足");
            return;
        }
        if (!EconomyManager.get().hasBalance(playerB.getUUID(), moneyOfferB)) {
            MessageUtil.sendError(playerB, "交易", "您的金币余额不足，交易中止！");
            cancelTrade("一方金币余额不足");
            return;
        }

        completedOrClosed = true;
        // 结算资金
        if (moneyOfferA > 0) {
            EconomyManager.get().transfer(playerA.getUUID(), playerB.getUUID(), moneyOfferA, "Trade with " + playerB.getName().getString());
        }
        if (moneyOfferB > 0) {
            EconomyManager.get().transfer(playerB.getUUID(), playerA.getUUID(), moneyOfferB, "Trade with " + playerA.getName().getString());
        }

        // 交换物品：A槽物品给B，B槽物品给A
        for (int slot : SLOTS_A) {
            ItemStack st = container.removeItemNoUpdate(slot);
            if (!st.isEmpty()) {
                giveOrDrop(playerB, st);
            }
        }
        for (int slot : SLOTS_B) {
            ItemStack st = container.removeItemNoUpdate(slot);
            if (!st.isEmpty()) {
                giveOrDrop(playerA, st);
            }
        }

        TradeManager.get().removeSession(playerA.getUUID(), playerB.getUUID());
        playerA.closeContainer();
        playerB.closeContainer();
        MessageUtil.sendSuccess(playerA, "交易", "与 " + playerB.getName().getString() + " 的交易已圆满完成！");
        MessageUtil.sendSuccess(playerB, "交易", "与 " + playerA.getName().getString() + " 的交易已圆满完成！");
        MessageUtil.playDing(playerA);
        MessageUtil.playDing(playerB);
    }

    public synchronized void cancelTrade(String reason) {
        if (completedOrClosed) return;
        completedOrClosed = true;

        for (int slot : SLOTS_A) {
            ItemStack st = container.removeItemNoUpdate(slot);
            if (!st.isEmpty()) {
                giveOrDrop(playerA, st);
            }
        }
        for (int slot : SLOTS_B) {
            ItemStack st = container.removeItemNoUpdate(slot);
            if (!st.isEmpty()) {
                giveOrDrop(playerB, st);
            }
        }

        TradeManager.get().removeSession(playerA.getUUID(), playerB.getUUID());
        playerA.closeContainer();
        playerB.closeContainer();
        MessageUtil.sendError(playerA, "交易", "交易已取消 (" + reason + ")，所有物品已安全退回。");
        MessageUtil.sendError(playerB, "交易", "交易已取消 (" + reason + ")，所有物品已安全退回。");
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private class TradeGuiMenu extends ChestMenu {
        private final boolean isSideA;

        public TradeGuiMenu(int syncId, Inventory playerInv, boolean isSideA) {
            super(MenuType.GENERIC_9x6, syncId, playerInv, container, 6);
            this.isSideA = isSideA;
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (completedOrClosed) return;
            if (clickType == ClickType.QUICK_MOVE) {
                // 禁止 Shift 快速塞入对方格子，仅允许手动放入己方槽位
                return;
            }
            if (slotId >= 0 && slotId < 54) {
                Set<Integer> mySlots = isSideA ? SLOTS_A : SLOTS_B;
                if (mySlots.contains(slotId)) {
                    resetReadyStates();
                    super.clicked(slotId, button, clickType, player);
                    return;
                }
                // 处理功能按钮点击
                if (slotId == 49) {
                    cancelTrade(player.getName().getString() + " 主动取消");
                    return;
                }
                if (isSideA) {
                    switch (slotId) {
                        case 36 -> adjustMoney(true, 10.0, false);
                        case 37 -> adjustMoney(true, 100.0, false);
                        case 38 -> adjustMoney(true, 1000.0, false);
                        case 39 -> adjustMoney(true, 0.0, true);
                        case 45 -> {
                            readyA = !readyA;
                            if (!readyA) confirmedA = false;
                            refreshDecorations();
                        }
                        case 46 -> {
                            if (readyA && readyB) {
                                confirmedA = true;
                                refreshDecorations();
                                tryCompleteTrade();
                            } else {
                                MessageUtil.sendError(playerA, "交易", "请等待双方均点击锁定报价后再点击最终确认！");
                            }
                        }
                    }
                } else {
                    switch (slotId) {
                        case 41 -> adjustMoney(false, 10.0, false);
                        case 42 -> adjustMoney(false, 100.0, false);
                        case 43 -> adjustMoney(false, 1000.0, false);
                        case 44 -> adjustMoney(false, 0.0, true);
                        case 53 -> {
                            readyB = !readyB;
                            if (!readyB) confirmedB = false;
                            refreshDecorations();
                        }
                        case 52 -> {
                            if (readyA && readyB) {
                                confirmedB = true;
                                refreshDecorations();
                                tryCompleteTrade();
                            } else {
                                MessageUtil.sendError(playerB, "交易", "请等待双方均点击锁定报价后再点击最终确认！");
                            }
                        }
                    }
                }
                return;
            }
            super.clicked(slotId, button, clickType, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (!completedOrClosed) {
                cancelTrade(player.getName().getString() + " 关闭了交易窗口");
            }
        }
    }
}
