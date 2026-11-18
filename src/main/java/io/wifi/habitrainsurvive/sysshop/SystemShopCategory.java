package io.wifi.habitrainsurvive.sysshop;

import java.util.ArrayList;
import java.util.List;

public class SystemShopCategory {
    public String id;
    public String displayName;
    public String iconItemId;
    public boolean dynamicEnabled = true;
    public ShopPurchasePolicy purchaseLimits = new ShopPurchasePolicy();
    public List<SystemShopItem> items = new ArrayList<>();

    public SystemShopCategory() {
    }

    public SystemShopCategory(String id, String displayName, String iconItemId) {
        this.id = id;
        this.displayName = displayName;
        this.iconItemId = iconItemId;
    }
}
