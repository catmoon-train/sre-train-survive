package io.wifi.habitrainsurvive.sysshop;

/** Personal and server-wide limits apply simultaneously to the same scope. */
public class ShopPurchasePolicy {
    public ShopPurchaseLimits personal = new ShopPurchaseLimits();
    public ShopPurchaseLimits global = new ShopPurchaseLimits();

    public void validate() {
        if (personal == null || global == null)
            throw new IllegalArgumentException("个人和全服购买限额不能为空");
        personal.validate();
        global.validate();
    }

    /** Add missing optional sections without replacing invalid existing settings. */
    public void migrate() {
        if (personal != null) personal.validate();
        if (global != null) global.validate();
        if (personal == null) personal = new ShopPurchaseLimits();
        if (global == null) global = new ShopPurchaseLimits();
    }

    public ShopPurchasePolicy copy() {
        validate();
        ShopPurchasePolicy copy = new ShopPurchasePolicy();
        copy.personal = personal.copy();
        copy.global = global.copy();
        return copy;
    }
}
