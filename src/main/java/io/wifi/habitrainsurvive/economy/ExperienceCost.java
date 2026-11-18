package io.wifi.habitrainsurvive.economy;

import java.util.Locale;

/** Optional alternate price for the system shop and lottery. Fixed by design; dynamic pricing only applies to coins. */
public final class ExperienceCost {
    public static final String NONE = "NONE", LEVELS = "LEVELS", POINTS = "POINTS";
    public static final int MAX_LEVELS = 1000, MAX_POINTS = 1_000_000;
    public String mode = NONE;
    public int amount = 0;

    public ExperienceCost() { }
    public ExperienceCost(String mode, int amount) { this.mode = mode; this.amount = amount; validate(); }

    public boolean enabled() { return !NONE.equals(mode) && amount > 0; }
    public boolean levels() { return LEVELS.equals(mode); }
    public boolean points() { return POINTS.equals(mode); }
    public String label() {
        if (!enabled()) return "未启用";
        return levels() ? amount + " 级经验" : amount + " 点经验";
    }
    public String label(int multiplier) {
        if (!enabled()) return "未启用";
        try {
            int total = scaledAmount(multiplier);
            return levels() ? total + " 级经验" : total + " 点经验";
        } catch (IllegalArgumentException overLimit) {
            return "超过可交易上限";
        }
    }
    public int scaledAmount(int multiplier) {
        if (!enabled()) return 0;
        if (multiplier < 1) throw new IllegalArgumentException("数量必须大于0");
        long total = (long) amount * multiplier;
        int max = levels() ? MAX_LEVELS * 64 : MAX_POINTS;
        if (total > max) throw new IllegalArgumentException("经验价格超过安全上限");
        return (int) total;
    }
    public void validate() {
        if (mode == null) mode = NONE;
        mode = mode.toUpperCase(Locale.ROOT);
        if (!NONE.equals(mode) && !LEVELS.equals(mode) && !POINTS.equals(mode))
            throw new IllegalArgumentException("经验价格模式无效");
        if (NONE.equals(mode)) { amount = 0; return; }
        int max = levels() ? MAX_LEVELS : MAX_POINTS;
        if (amount < 1 || amount > max) throw new IllegalArgumentException("经验价格必须为1到" + max);
    }
    /** Missing/old data is intentionally disabled, never silently charged as experience. */
    public void migrate() {
        try { validate(); }
        catch (IllegalArgumentException invalid) { mode = NONE; amount = 0; }
    }
    public ExperienceCost copy() { ExperienceCost copy = new ExperienceCost(); copy.mode = mode; copy.amount = amount; return copy; }
    public static ExperienceCost none() { return new ExperienceCost(); }
}
