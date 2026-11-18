package io.wifi.habitrainsurvive.gift;

import java.util.UUID;

public class GiftPackage {
    public String id;
    public UUID senderUuid;
    public String senderName;
    public String serializedItem;
    public String itemDisplayName;
    public int itemCount;
    public double attachedCoins;
    public String message;
    public long sentAt;

    public GiftPackage() {
    }

    public GiftPackage(String id, UUID senderUuid, String senderName, String serializedItem,
                       String itemDisplayName, int itemCount, double attachedCoins, String message) {
        this.id = id;
        this.senderUuid = senderUuid;
        this.senderName = senderName;
        this.serializedItem = serializedItem;
        this.itemDisplayName = itemDisplayName;
        this.itemCount = itemCount;
        this.attachedCoins = attachedCoins;
        this.message = message;
        this.sentAt = System.currentTimeMillis();
    }
}
