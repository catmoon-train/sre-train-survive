package io.wifi.habitrainsurvive.sysshop;

import io.wifi.habitrainsurvive.economy.ExperienceCost;

public class SystemShopItem {
    public String serializedItem;
    public String displayName;
    public double buyPrice;  // 基准价；0 表示禁用此方向
    public double sellPrice;
    public String id = java.util.UUID.randomUUID().toString();
    public boolean dynamicEnabled = true;
    public ShopPurchasePolicy purchaseLimits = new ShopPurchasePolicy();
    public SystemShopPricing pricing;
    public ExperienceCost experiencePrice = new ExperienceCost();
    public double pressure;
    public long pressureUpdatedAt;

    public SystemShopItem() { }

    public SystemShopItem(String serializedItem, String displayName, double buyPrice, double sellPrice) {
        this.serializedItem = serializedItem;
        this.displayName = displayName;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.pricing = SystemShopPricing.defaults(buyPrice, sellPrice);
        this.pressureUpdatedAt = System.currentTimeMillis();
    }

    /** Legacy JSON keeps its base prices; absent/invalid dynamic data receives safe defaults. */
    public void migrate(long now) {
        if (id == null || id.isBlank()) id = java.util.UUID.randomUUID().toString();
        if (purchaseLimits == null) purchaseLimits = new ShopPurchasePolicy();
        purchaseLimits.migrate();
        if (experiencePrice == null) experiencePrice = new ExperienceCost();
        experiencePrice.migrate();
        buyPrice = safeBase(buyPrice);
        sellPrice = safeBase(sellPrice);
        if (buyPrice > 0 && sellPrice > buyPrice) sellPrice = buyPrice;
        if (pricing == null) pricing = SystemShopPricing.defaults(buyPrice, sellPrice);
        try {
            pricing.validate(buyPrice, sellPrice);
        } catch (IllegalArgumentException invalid) {
            pricing = SystemShopPricing.defaults(buyPrice, sellPrice);
        }
        if (!Double.isFinite(pressure)) pressure = 0;
        pressure = DynamicShopPrice.clampPressure(pricing, pressure);
        if (pressureUpdatedAt <= 0) pressureUpdatedAt = now;
        if (displayName == null || displayName.isBlank()) displayName = "未命名商品";
    }

    private static double safeBase(double price) {
        return Double.isFinite(price) && price > 0 ? Math.min(price, SystemShopPricing.MAX_PRICE) : 0;
    }

    public SystemShopItem copy() {
        SystemShopItem item = new SystemShopItem();
        item.serializedItem = serializedItem;
        item.displayName = displayName;
        item.buyPrice = buyPrice;
        item.sellPrice = sellPrice;
        item.pricing = pricing.copy();
        item.id = id;
        item.dynamicEnabled = dynamicEnabled;
        item.purchaseLimits = purchaseLimits.copy();
        item.pressure = pressure;
        item.pressureUpdatedAt = pressureUpdatedAt;
        item.experiencePrice = experiencePrice.copy();
        return item;
    }
}
