package io.wifi.habitrainsurvive.chestshop;

/** Pure validation shared by physical and electronic shops. Money is denominated in cents. */
public final class ShopTradeRules {
    public static final double MAX_PRICE = 1_000_000_000;
    private ShopTradeRules() { }

    public static boolean price(double value, boolean allowDisabled) {
        return Double.isFinite(value) && value <= MAX_PRICE
                && (allowDisabled && value == 0 || value >= 0.01);
    }

    public static double total(double price, int quantity) {
        if (!price(price, false) || quantity <= 0 || quantity > 1_000_000) return -1;
        double total = Math.round(price * quantity * 100.0) / 100.0;
        return Double.isFinite(total) && total > 0 ? total : -1;
    }
}
