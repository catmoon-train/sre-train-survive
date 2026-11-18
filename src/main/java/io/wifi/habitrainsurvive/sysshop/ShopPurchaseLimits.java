package io.wifi.habitrainsurvive.sysshop;

/** Item counts; zero means unlimited, independently for each limit. */
public class ShopPurchaseLimits {
    public static final int MAX = 1_000_000_000;

    public int perTransaction;
    public int daily;
    public int weekly;
    public int monthly;
    public int total;

    public void validate() {
        check("单次", perTransaction);
        check("每日", daily);
        check("每周", weekly);
        check("每月", monthly);
        check("累计", total);
    }

    private static void check(String name, int value) {
        if (value < 0 || value > MAX)
            throw new IllegalArgumentException(name + "购买限额必须为 0.." + MAX);
    }

    public ShopPurchaseLimits copy() {
        ShopPurchaseLimits copy = new ShopPurchaseLimits();
        copy.perTransaction = perTransaction;
        copy.daily = daily;
        copy.weekly = weekly;
        copy.monthly = monthly;
        copy.total = total;
        return copy;
    }
}
