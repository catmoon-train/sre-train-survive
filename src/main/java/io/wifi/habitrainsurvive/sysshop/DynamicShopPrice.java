package io.wifi.habitrainsurvive.sysshop;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure exponential demand curve. Quotes never mutate persisted state. */
public final class DynamicShopPrice {
    public static final int MAX_QUANTITY = 100_000;
    private DynamicShopPrice() { }

    public record Quote(long cents, double pressure, long timestamp) {
        public double total() { return cents / 100.0; }
    }

    public static double clampPressure(SystemShopPricing p, double pressure) {
        return Math.max(Math.log(p.minMultiplier), Math.min(Math.log(p.maxMultiplier), pressure));
    }

    public static double recovered(SystemShopItem item, long now) {
        double pressure = clampPressure(item.pricing, item.pressure);
        // A clock rollback must not amplify demand or give multiple recoveries for the same elapsed time.
        double elapsed = Math.max(0.0, (double) now - item.pressureUpdatedAt);
        return pressure * Math.exp(-Math.log(2) * elapsed / (item.pricing.halfLifeHours * 3_600_000.0));
    }

    public static double price(SystemShopItem item, boolean buy, double pressure) {
        double base = buy ? item.buyPrice : item.sellPrice;
        if (base <= 0) return 0;
        SystemShopPricing p = item.pricing;
        double min = buy ? p.minBuyPrice : p.minSellPrice;
        double max = buy ? p.maxBuyPrice : p.maxSellPrice;
        return Math.max(min, Math.min(max, base * Math.exp(clampPressure(p, pressure))));
    }

    public static Quote quote(SystemShopItem item, boolean buy, int count, long now) {
        return quote(item, buy, count, now, item.dynamicEnabled);
    }

    public static Quote quote(SystemShopItem item, boolean buy, int count, long now, boolean dynamicEnabled) {
        if (count < 1 || count > MAX_QUANTITY) throw new IllegalArgumentException("无效交易数量");
        if (item.pricing == null) throw new IllegalArgumentException("商品尚未迁移");
        if (!Double.isFinite(item.pressure)) throw new IllegalArgumentException("无效供需压力，请管理员重置");
        item.pricing.validate(item.buyPrice, item.sellPrice);
        if ((buy ? item.buyPrice : item.sellPrice) <= 0) throw new IllegalArgumentException("此交易方向已禁用");
        if (!dynamicEnabled) {
            double base = buy ? item.buyPrice : item.sellPrice;
            long unit = BigDecimal.valueOf(base).movePointRight(2)
                    .setScale(0, buy ? RoundingMode.CEILING : RoundingMode.FLOOR).longValueExact();
            return new Quote(Math.multiplyExact(unit, count), 0, Math.max(now, item.pressureUpdatedAt));
        }
        double pressure = recovered(item, now);
        double reference = item.buyPrice > 0 ? item.buyPrice : item.sellPrice;
        double step = Math.log1p(item.pricing.changePercent / 100.0 + item.pricing.changeAmount / reference)
                / item.pricing.sensitivityVolume;
        long cents = 0;
        for (int n = 0; n < count; n++) {
            double next = clampPressure(item.pricing, pressure + (buy ? step : -step));
            // Both directions price the upper end of the SAME unit edge. Reversing an edge cannot profit.
            // At saturation, clipping discards excess demand instead of creating a hidden recovery debt.
            double unit = price(item, buy, Math.max(pressure, next));
            long unitCents = BigDecimal.valueOf(unit).movePointRight(2)
                    .setScale(0, buy ? RoundingMode.CEILING : RoundingMode.FLOOR).longValueExact();
            cents = Math.addExact(cents, unitCents);
            pressure = next;
        }
        return new Quote(cents, pressure, Math.max(now, item.pressureUpdatedAt));
    }

    public static void apply(SystemShopItem item, Quote quote) {
        item.pressure = quote.pressure;
        item.pressureUpdatedAt = quote.timestamp;
    }
}
