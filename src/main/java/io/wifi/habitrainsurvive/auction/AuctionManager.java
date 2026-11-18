package io.wifi.habitrainsurvive.auction;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class AuctionManager {
    private static final AuctionManager INSTANCE = new AuctionManager();

    public static class Storage {
        public double taxRate = 0.05; // 5% 拍卖行成交税
        public int maxListingsPerPlayer = 10;
        public int nextIdSequence = 1001;
        public Map<String, AuctionListing> activeListings = new ConcurrentHashMap<>();
        // 离线/背包满时的待领取物品邮箱: PlayerUUID -> List<SerializedItemJson>
        public Map<UUID, List<String>> claimMailbox = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();

    public static AuctionManager get() {
        return INSTANCE;
    }

    public void load(Path path) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.activeListings == null) this.storage.activeListings = new ConcurrentHashMap<>();
        if (this.storage.claimMailbox == null) this.storage.claimMailbox = new ConcurrentHashMap<>();
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    public synchronized String nextAuctionId() {
        return String.valueOf(storage.nextIdSequence++);
    }

    public int getMaxListingsPerPlayer() {
        return storage.maxListingsPerPlayer;
    }

    public double getTaxRate() {
        return storage.taxRate;
    }

    public List<AuctionListing> getActiveListingsSorted() {
        return storage.activeListings.values().stream()
                .filter(a -> !a.isExpired())
                .sorted(Comparator.comparingLong((AuctionListing a) -> a.createdAt).reversed())
                .collect(Collectors.toList());
    }

    public List<AuctionListing> getPlayerListings(UUID seller) {
        return storage.activeListings.values().stream()
                .filter(a -> a.sellerUuid.equals(seller))
                .collect(Collectors.toList());
    }

    public AuctionListing getListing(String id) {
        return storage.activeListings.get(id);
    }

    public void addListing(AuctionListing listing) {
        storage.activeListings.put(listing.id, listing);
    }

    public void addToMailbox(UUID playerUuid, String serializedItem) {
        storage.claimMailbox.computeIfAbsent(playerUuid, k -> new ArrayList<>()).add(serializedItem);
    }

    public List<String> getMailbox(UUID playerUuid) {
        return storage.claimMailbox.getOrDefault(playerUuid, Collections.emptyList());
    }

    public void deliverOrMailbox(MinecraftServer server, UUID targetUuid, String serializedItem, String notice) {
        ServerPlayer online = server.getPlayerList().getPlayer(targetUuid);
        if (online != null) {
            ItemStack stack = ItemSerializer.deserialize(serializedItem, server.registryAccess());
            if (!stack.isEmpty() && online.getInventory().add(stack)) {
                MessageUtil.sendSuccess(online, "拍卖行", notice + " 物品已直接发放到您的背包！");
                MessageUtil.playDing(online);
                return;
            }
            MessageUtil.sendInfo(online, "拍卖行", notice + " 您的背包已满，物品已存入拍卖行邮箱，请使用 /ah claim 领取！");
        }
        addToMailbox(targetUuid, serializedItem);
    }

    public synchronized boolean buyNow(ServerPlayer buyer, String listingId) {
        AuctionListing listing = storage.activeListings.get(listingId);
        if (listing == null || listing.isExpired()) {
            MessageUtil.sendError(buyer, "拍卖行", "该拍品不存在或已结束！");
            return false;
        }
        if (listing.sellerUuid.equals(buyer.getUUID())) {
            MessageUtil.sendError(buyer, "拍卖行", "您不能购买自己上架的拍品（如需取回请使用 /ah cancel " + listingId + "）！");
            return false;
        }
        double price = listing.biddingMode ? listing.buyoutPrice : listing.startOrBuyPrice;
        if (price <= 0) {
            MessageUtil.sendError(buyer, "拍卖行", "该竞拍商品未设置一口价，请使用 /ah bid " + listingId + " <金额> 参与竞价！");
            return false;
        }
        if (!EconomyManager.get().withdraw(buyer.getUUID(), price, "AH Buyout #" + listingId)) {
            MessageUtil.sendError(buyer, "拍卖行", "余额不足！一口价购买需要 " + EconomyManager.get().format(price));
            return false;
        }

        storage.activeListings.remove(listingId);
        // 如果之前有人竞拍出价，退还前最高出价者的冻结资金
        if (listing.biddingMode && listing.highestBidderUuid != null && listing.currentBid > 0) {
            EconomyManager.get().deposit(listing.highestBidderUuid, listing.currentBid, "AH Outbid Refund #" + listingId);
        }

        double netIncome = Math.round(price * (1.0 - storage.taxRate) * 100.0) / 100.0;
        EconomyManager.get().deposit(listing.sellerUuid, netIncome, "AH Sold #" + listingId);

        deliverOrMailbox(buyer.server, buyer.getUUID(), listing.serializedItem,
                "成功以 " + EconomyManager.get().format(price) + " 购得 [" + listing.itemDisplayName + " x" + listing.count + "]！");

        ServerPlayer sellerOnline = buyer.server.getPlayerList().getPlayer(listing.sellerUuid);
        if (sellerOnline != null) {
            MessageUtil.sendSuccess(sellerOnline, "拍卖行", "您的拍品 [" + listing.itemDisplayName + "] 已被 "
                    + buyer.getName().getString() + " 购买！税后收入: " + EconomyManager.get().format(netIncome));
            MessageUtil.playDing(sellerOnline);
        }
        return true;
    }

    public synchronized boolean placeBid(ServerPlayer bidder, String listingId, double bidAmount) {
        AuctionListing listing = storage.activeListings.get(listingId);
        if (listing == null || listing.isExpired()) {
            MessageUtil.sendError(bidder, "拍卖行", "该拍品不存在或已结束！");
            return false;
        }
        if (!listing.biddingMode) {
            MessageUtil.sendError(bidder, "拍卖行", "该商品为一口价商品，请直接使用 /ah buy " + listingId + " 购买！");
            return false;
        }
        if (listing.sellerUuid.equals(bidder.getUUID())) {
            MessageUtil.sendError(bidder, "拍卖行", "不能竞拍自己的商品！");
            return false;
        }
        double minRequired = listing.currentBid > 0 ? Math.round((listing.currentBid * 1.05) * 100.0) / 100.0 : listing.startOrBuyPrice;
        if (bidAmount < minRequired) {
            MessageUtil.sendError(bidder, "拍卖行", "出价过低！当前最低需出价: " + EconomyManager.get().format(minRequired));
            return false;
        }
        if (listing.buyoutPrice > 0 && bidAmount >= listing.buyoutPrice) {
            return buyNow(bidder, listingId);
        }
        if (!EconomyManager.get().withdraw(bidder.getUUID(), bidAmount, "AH Bid #" + listingId)) {
            MessageUtil.sendError(bidder, "拍卖行", "您的余额不足以冻结出价 " + EconomyManager.get().format(bidAmount));
            return false;
        }

        // 退还上一位竞价者的冻结资金
        if (listing.highestBidderUuid != null && listing.currentBid > 0) {
            EconomyManager.get().deposit(listing.highestBidderUuid, listing.currentBid, "AH Outbid Refund #" + listingId);
            ServerPlayer prevBidder = bidder.server.getPlayerList().getPlayer(listing.highestBidderUuid);
            if (prevBidder != null) {
                MessageUtil.sendInfo(prevBidder, "拍卖行", "您在拍品 #" + listingId + " [" + listing.itemDisplayName
                        + "] 的出价已被超越，冻结资金 " + EconomyManager.get().format(listing.currentBid) + " 已退回！");
            }
        }

        listing.currentBid = bidAmount;
        listing.highestBidderUuid = bidder.getUUID();
        listing.highestBidderName = bidder.getName().getString();
        // 最后1分钟内有人加价则自动延时 60 秒防止秒拍偷鸡
        if (listing.getRemainingSeconds() < 60) {
            listing.expiresAt += 60_000L;
        }

        MessageUtil.sendSuccess(bidder, "拍卖行", "竞价成功！您当前以 " + EconomyManager.get().format(bidAmount) + " 领跑拍品 #" + listingId + " [" + listing.itemDisplayName + "]！");
        return true;
    }

    public synchronized boolean cancelListing(ServerPlayer player, String listingId) {
        AuctionListing listing = storage.activeListings.get(listingId);
        if (listing == null) {
            MessageUtil.sendError(player, "拍卖行", "未找到拍品 #" + listingId);
            return false;
        }
        if (!listing.sellerUuid.equals(player.getUUID()) && !player.hasPermissions(2)) {
            MessageUtil.sendError(player, "拍卖行", "您无权下架他人的拍品！");
            return false;
        }
        if (listing.biddingMode && listing.highestBidderUuid != null && !player.hasPermissions(2)) {
            MessageUtil.sendError(player, "拍卖行", "该竞拍已有玩家出价，无法中途悔拍下架！");
            return false;
        }
        storage.activeListings.remove(listingId);
        if (listing.biddingMode && listing.highestBidderUuid != null && listing.currentBid > 0) {
            EconomyManager.get().deposit(listing.highestBidderUuid, listing.currentBid, "AH Cancelled Refund #" + listingId);
        }
        deliverOrMailbox(player.server, listing.sellerUuid, listing.serializedItem, "拍品 #" + listingId + " 已下架，");
        return true;
    }

    public void tick(MinecraftServer server) {
        List<String> expiredIds = new ArrayList<>();
        for (AuctionListing listing : storage.activeListings.values()) {
            if (listing.isExpired()) {
                expiredIds.add(listing.id);
            }
        }
        for (String id : expiredIds) {
            AuctionListing listing = storage.activeListings.remove(id);
            if (listing == null) continue;
            if (listing.biddingMode && listing.highestBidderUuid != null && listing.currentBid > 0) {
                double netIncome = Math.round(listing.currentBid * (1.0 - storage.taxRate) * 100.0) / 100.0;
                EconomyManager.get().deposit(listing.sellerUuid, netIncome, "AH Bid Won #" + listing.id);
                deliverOrMailbox(server, listing.highestBidderUuid, listing.serializedItem,
                        "恭喜您成功竞拍赢得 #" + listing.id + " [" + listing.itemDisplayName + "]！");
                ServerPlayer seller = server.getPlayerList().getPlayer(listing.sellerUuid);
                if (seller != null) {
                    MessageUtil.sendSuccess(seller, "拍卖行", "您的竞拍品 #" + listing.id + " [" + listing.itemDisplayName
                            + "] 已由 " + listing.highestBidderName + " 拍下，税后收入: " + EconomyManager.get().format(netIncome));
                }
            } else {
                deliverOrMailbox(server, listing.sellerUuid, listing.serializedItem,
                        "您的拍品 #" + listing.id + " [" + listing.itemDisplayName + "] 已到期流拍，");
            }
        }
    }

    public int claimAllMailbox(ServerPlayer player) {
        List<String> box = storage.claimMailbox.get(player.getUUID());
        if (box == null || box.isEmpty()) {
            MessageUtil.sendInfo(player, "拍卖行", "您的拍卖行待领取邮箱为空。");
            return 0;
        }
        int claimed = 0;
        Iterator<String> it = box.iterator();
        while (it.hasNext()) {
            String json = it.next();
            ItemStack stack = ItemSerializer.deserialize(json, player.server.registryAccess());
            if (!stack.isEmpty()) {
                if (player.getInventory().add(stack)) {
                    it.remove();
                    claimed++;
                } else {
                    MessageUtil.sendError(player, "拍卖行", "背包空间不足，已领取 " + claimed + " 件，剩余 " + box.size() + " 件请清理背包后再次 /ah claim。");
                    return claimed;
                }
            } else {
                it.remove();
            }
        }
        MessageUtil.sendSuccess(player, "拍卖行", "已从拍卖行邮箱成功领取 " + claimed + " 件物品！");
        MessageUtil.playDing(player);
        return claimed;
    }
}
