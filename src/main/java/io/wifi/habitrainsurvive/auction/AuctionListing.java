package io.wifi.habitrainsurvive.auction;

import java.util.UUID;

public class AuctionListing {
    public String id;
    public UUID sellerUuid;
    public String sellerName;
    public String serializedItem;
    public String itemDisplayName;
    public int count;
    public boolean biddingMode; // true = 竞拍模式, false = 一口价模式
    public double startOrBuyPrice;
    public double buyoutPrice;
    public double currentBid;
    public UUID highestBidderUuid;
    public String highestBidderName;
    public long createdAt;
    public long expiresAt;

    public AuctionListing() {
    }

    public AuctionListing(String id, UUID sellerUuid, String sellerName, String serializedItem,
                          String itemDisplayName, int count, boolean biddingMode,
                          double startOrBuyPrice, double buyoutPrice, long durationMinutes) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.serializedItem = serializedItem;
        this.itemDisplayName = itemDisplayName;
        this.count = count;
        this.biddingMode = biddingMode;
        this.startOrBuyPrice = startOrBuyPrice;
        this.buyoutPrice = buyoutPrice;
        this.currentBid = 0.0;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = this.createdAt + durationMinutes * 60_000L;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    public long getRemainingSeconds() {
        return Math.max(0L, (expiresAt - System.currentTimeMillis()) / 1000L);
    }
}
