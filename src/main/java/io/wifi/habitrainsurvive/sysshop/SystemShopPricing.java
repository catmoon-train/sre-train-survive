package io.wifi.habitrainsurvive.sysshop;

/** Pure, registry-independent configuration. Absolute bounds and percentage bounds both apply. */
public final class SystemShopPricing {
    public static final double MAX_PRICE = 1_000_000_000.0;
    public double minBuyPrice;
    public double maxBuyPrice;
    public double minSellPrice;
    public double maxSellPrice;
    public double minMultiplier = 0.5;
    public double maxMultiplier = 2.0;
    public double changeAmount = 0.0;
    public double changePercent = 1.0;
    public int sensitivityVolume = 64;
    public double halfLifeHours = 6.0;

    public static SystemShopPricing defaults(double buy, double sell) {
        SystemShopPricing p = new SystemShopPricing();
        // Outward rounding leaves the original base inside the envelope, including sub-cent legacy prices.
        p.minBuyPrice = Math.floor(buy * 50.0) / 100.0;
        p.maxBuyPrice = buy == 0 ? MAX_PRICE : Math.min(MAX_PRICE, Math.ceil(buy * 200.0) / 100.0);
        p.minSellPrice = Math.floor(sell * 50.0) / 100.0;
        p.maxSellPrice = sell == 0 ? MAX_PRICE : Math.min(MAX_PRICE, Math.ceil(sell * 200.0) / 100.0);
        return p;
    }

    public SystemShopPricing copy() {
        SystemShopPricing p = new SystemShopPricing();
        p.minBuyPrice = minBuyPrice;
        p.maxBuyPrice = maxBuyPrice;
        p.minSellPrice = minSellPrice;
        p.maxSellPrice = maxSellPrice;
        p.minMultiplier = minMultiplier;
        p.maxMultiplier = maxMultiplier;
        p.changeAmount = changeAmount;
        p.changePercent = changePercent;
        p.sensitivityVolume = sensitivityVolume;
        p.halfLifeHours = halfLifeHours;
        return p;
    }

    public void validate(double buy, double sell) {
        range(buy, 0, MAX_PRICE, "购买基准价");
        range(sell, 0, MAX_PRICE, "回收基准价");
        money(minBuyPrice); money(maxBuyPrice); money(minSellPrice); money(maxSellPrice);
        if (minBuyPrice > maxBuyPrice || minSellPrice > maxSellPrice)
            throw new IllegalArgumentException("最低价格不能高于最高价格");
        if ((buy > 0 && (buy < minBuyPrice || buy > maxBuyPrice))
                || (sell > 0 && (sell < minSellPrice || sell > maxSellPrice)))
            throw new IllegalArgumentException("基准价必须位于对应的绝对上下限内；请先扩大上下限");
        range(minMultiplier, 0.000001, 1, "最低倍率");
        range(maxMultiplier, 1, 1000, "最高倍率");
        range(changeAmount, 0, MAX_PRICE, "变化金额");
        range(changePercent, 0, 100, "变化百分比");
        if (sensitivityVolume < 1 || sensitivityVolume > 1_000_000)
            throw new IllegalArgumentException("灵敏度数量必须为 1..1000000");
        range(halfLifeHours, 1.0 / 60, 87600, "恢复半衰期(小时)");
        // A common monotone multiplier, ordered bases AND ordered clamps prohibit crossed spreads.
        if (buy > 0 && sell > 0 && (sell > buy || minSellPrice > minBuyPrice || maxSellPrice > maxBuyPrice))
            throw new IllegalArgumentException("回收基准价/最低价/最高价均不能高于对应购买价格");
    }

    public static double range(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(name + " 必须为有限数字，范围 " + min + ".." + max);
        return value;
    }

    public static double money(double value) {
        range(value, 0, MAX_PRICE, "金额");
        if (Math.abs(value * 100 - Math.rint(value * 100)) > 0.00001)
            throw new IllegalArgumentException("金额最多保留两位小数");
        return value;
    }
}
