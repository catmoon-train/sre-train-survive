package io.wifi.habitrainsurvive.cmi;

/** Registry-independent arithmetic for permanent, UUID-bound home capacity upgrades. */
public final class HomeSlotUpgrades {
    private HomeSlotUpgrades() { }
    public static long nextPrice(int purchased) {
        validate(purchased);
        if (purchased == Integer.MAX_VALUE) throw new IllegalArgumentException("已达到可购买额度的安全上限");
        return 100L + 400L * purchased;
    }
    public static int maximum(int base, int purchased) {
        validate(purchased);
        if (base < 0) throw new IllegalArgumentException("基础家额度不能为负数");
        return (int) Math.min(Integer.MAX_VALUE, (long) base + purchased);
    }
    public static void validate(int purchased) {
        if (purchased < 0) throw new IllegalArgumentException("已购买家额度数据无效，不能为负数");
    }
}
