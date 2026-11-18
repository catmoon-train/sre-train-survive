package io.wifi.habitrainsurvive.auction;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
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

public class AuctionGui {
    public static void open(ServerPlayer player, int page) {
        List<AuctionListing> all = AuctionManager.get().getActiveListingsSorted();
        int pageSize = 45;
        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) pageSize));
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, all.size());

        SimpleContainer container = new SimpleContainer(54);
        List<AuctionListing> pageListings = all.subList(start, end);

        for (int i = 0; i < pageListings.size(); i++) {
            AuctionListing listing = pageListings.get(i);
            ItemStack display = ItemSerializer.deserialize(listing.serializedItem, player.server.registryAccess()).copy();
            if (display.isEmpty()) {
                display = new ItemStack(Items.PAPER);
            }
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("拍品编号: #" + listing.id).withStyle(ChatFormatting.DARK_GRAY));
            lore.add(Component.literal("卖家: " + listing.sellerName).withStyle(ChatFormatting.YELLOW));
            if (listing.biddingMode) {
                lore.add(Component.literal("模式: 竞拍模式").withStyle(ChatFormatting.LIGHT_PURPLE));
                lore.add(Component.literal("起拍价: " + EconomyManager.get().format(listing.startOrBuyPrice)).withStyle(ChatFormatting.GRAY));
                lore.add(Component.literal("当前最高出价: " + EconomyManager.get().format(listing.currentBid)
                        + (listing.highestBidderName != null ? " (" + listing.highestBidderName + ")" : " (暂无出价)")).withStyle(ChatFormatting.GOLD));
                if (listing.buyoutPrice > 0) {
                    lore.add(Component.literal("一口价: " + EconomyManager.get().format(listing.buyoutPrice)).withStyle(ChatFormatting.GREEN));
                }
                lore.add(Component.literal("▶ 左键点击: 加价 5% 参与竞拍").withStyle(ChatFormatting.AQUA));
                if (listing.buyoutPrice > 0) {
                    lore.add(Component.literal("▶ 右键点击: 一口价直接买断").withStyle(ChatFormatting.GREEN));
                }
            } else {
                lore.add(Component.literal("模式: 一口价直售").withStyle(ChatFormatting.GREEN));
                lore.add(Component.literal("一口价: " + EconomyManager.get().format(listing.startOrBuyPrice)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                lore.add(Component.literal("▶ 点击直接购买此拍品").withStyle(ChatFormatting.AQUA));
            }
            lore.add(Component.literal("剩余时间: " + (listing.getRemainingSeconds() / 60) + "分" + (listing.getRemainingSeconds() % 60) + "秒").withStyle(ChatFormatting.GRAY));
            display.set(DataComponents.LORE, new ItemLore(lore));
            container.setItem(i, display);
        }

        // 底部导航栏 (45..53)
        ItemStack glass = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        glass.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int s = 45; s < 54; s++) {
            container.setItem(s, glass.copy());
        }

        if (currentPage > 1) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("« 上一页 (第 " + (currentPage - 1) + " 页)").withStyle(ChatFormatting.YELLOW));
            container.setItem(45, prev);
        }

        ItemStack mailbox = new ItemStack(Items.ENDER_CHEST);
        int mailCount = AuctionManager.get().getMailbox(player.getUUID()).size();
        mailbox.set(DataComponents.CUSTOM_NAME, Component.literal("领取待收物品邮箱 (" + mailCount + " 件)").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        mailbox.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("点击提取流拍或竞拍赢得的物品").withStyle(ChatFormatting.GRAY))));
        container.setItem(48, mailbox);

        ItemStack info = new ItemStack(Items.GOLD_INGOT);
        info.set(DataComponents.CUSTOM_NAME, Component.literal("我的余额: " + EconomyManager.get().format(EconomyManager.get().getBalance(player.getUUID()))).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        info.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("上架一口价: /ah sell <价格> [分钟]").withStyle(ChatFormatting.YELLOW),
                Component.literal("上架竞拍品: /ah bidstart <起拍价> <一口价> [分钟]").withStyle(ChatFormatting.AQUA)
        )));
        container.setItem(49, info);

        ItemStack refresh = new ItemStack(Items.SUNFLOWER);
        refresh.set(DataComponents.CUSTOM_NAME, Component.literal("刷新拍卖行列表").withStyle(ChatFormatting.GREEN));
        container.setItem(50, refresh);

        if (currentPage < totalPages) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("下一页 (第 " + (currentPage + 1) + " 页) »").withStyle(ChatFormatting.YELLOW));
            container.setItem(53, next);
        }

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inv, container, 6) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId >= 0 && slotId < pageListings.size()) {
                    AuctionListing clickedListing = pageListings.get(slotId);
                    if (clickedListing.biddingMode) {
                        if (button == 1 && clickedListing.buyoutPrice > 0) {
                            AuctionManager.get().buyNow(sp, clickedListing.id);
                        } else {
                            double nextBid = clickedListing.currentBid > 0
                                    ? Math.round(clickedListing.currentBid * 1.05 * 100.0) / 100.0
                                    : clickedListing.startOrBuyPrice;
                            AuctionManager.get().placeBid(sp, clickedListing.id, nextBid);
                        }
                    } else {
                        AuctionManager.get().buyNow(sp, clickedListing.id);
                    }
                    open(sp, currentPage);
                    return;
                }
                if (slotId == 45 && currentPage > 1) {
                    open(sp, currentPage - 1);
                } else if (slotId == 48) {
                    AuctionManager.get().claimAllMailbox(sp);
                    open(sp, currentPage);
                } else if (slotId == 50) {
                    open(sp, currentPage);
                    MessageUtil.playDing(sp);
                } else if (slotId == 53 && currentPage < totalPages) {
                    open(sp, currentPage + 1);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("全球拍卖行 (第 " + currentPage + "/" + totalPages + " 页)")));
    }
}
