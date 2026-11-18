package io.wifi.habitrainsurvive.sysshop;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DynamicShopPriceTest {
    private static final long NOW = 1_700_000_000_000L;

    private static SystemShopItem item(double buy, double sell) {
        SystemShopItem item = new SystemShopItem("components", "test", buy, sell);
        item.pressureUpdatedAt = NOW;
        return item;
    }

    @Test void defaultVolumeRaisesAndLowersTheCommonMultiplierByAboutOnePercent() {
        SystemShopItem item = item(100, 30);
        DynamicShopPrice.Quote buy = DynamicShopPrice.quote(item, true, 64, NOW);
        assertEquals(1.01, Math.exp(buy.pressure()), 1e-12);
        assertEquals(0, item.pressure, "Preview must not mutate state");
        DynamicShopPrice.Quote sell = DynamicShopPrice.quote(item, false, 64, NOW);
        assertEquals(1 / 1.01, Math.exp(sell.pressure()), 1e-12);
        assertTrue(buy.total() > 6400);
        assertTrue(sell.total() < 1920);
    }

    @Test void pressureAndPricesSaturateWithoutHiddenDemandDebt() {
        SystemShopItem item = item(100, 30);
        DynamicShopPrice.apply(item, DynamicShopPrice.quote(item, true, 100_000, NOW));
        assertEquals(Math.log(2), item.pressure);
        assertEquals(200, DynamicShopPrice.price(item, true, item.pressure), 1e-10);
        assertEquals(60, DynamicShopPrice.price(item, false, item.pressure), 1e-10);
        DynamicShopPrice.apply(item, DynamicShopPrice.quote(item, false, 100_000, NOW));
        assertEquals(Math.log(0.5), item.pressure);
        assertEquals(50, DynamicShopPrice.price(item, true, item.pressure), 1e-10);
        DynamicShopPrice.apply(item, DynamicShopPrice.quote(item, true, 1, NOW));
        assertTrue(item.pressure > Math.log(0.5));
    }

    @Test void absoluteAndRelativeBoundsBothApply() {
        SystemShopItem item = item(100, 30);
        item.pricing.minBuyPrice = 80;
        item.pricing.maxBuyPrice = 120;
        item.pricing.minSellPrice = 25;
        item.pricing.maxSellPrice = 40;
        item.pricing.validate(100, 30);
        assertEquals(80, DynamicShopPrice.price(item, true, -10));
        assertEquals(120, DynamicShopPrice.price(item, true, 10));
        assertEquals(25, DynamicShopPrice.price(item, false, -10));
        assertEquals(40, DynamicShopPrice.price(item, false, 10));
    }

    @Test void sixHourHalfLifeWorksForPositiveAndNegativePressureAndAfterLongDowntime() {
        SystemShopItem item = item(100, 30);
        for (double pressure : new double[]{0.6, -0.6}) {
            item.pressure = pressure;
            assertEquals(pressure / 2, DynamicShopPrice.recovered(item, NOW + 6 * 3_600_000L), 1e-12);
            assertEquals(pressure / 4, DynamicShopPrice.recovered(item, NOW + 12 * 3_600_000L), 1e-12);
            assertEquals(0, DynamicShopPrice.recovered(item, NOW + 365L * 24 * 3_600_000), 1e-12);
        }
    }

    @Test void clockRollbackNeverAmplifiesPressureOrMovesCheckpointBackwards() {
        SystemShopItem item = item(100, 30);
        item.pressure = 0.5;
        assertEquals(0.5, DynamicShopPrice.recovered(item, NOW - 100_000));
        DynamicShopPrice.Quote quote = DynamicShopPrice.quote(item, true, 1, NOW - 100_000);
        assertEquals(NOW, quote.timestamp());
        DynamicShopPrice.apply(item, quote);
        assertEquals(quote.pressure(), DynamicShopPrice.recovered(item, NOW));
    }

    @Test void absoluteStepAndPercentageContributeToTheSameExponentialCurve() {
        SystemShopItem item = item(100, 30);
        item.pricing.changeAmount = 2;
        item.pricing.changePercent = 3;
        item.pricing.sensitivityVolume = 10;
        assertEquals(1.05, Math.exp(DynamicShopPrice.quote(item, true, 10, NOW).pressure()), 1e-12);
    }

    @Test void bulkAndSinglesMatchExactlyIncludingCentsRecoveryAndSaturation() {
        for (boolean buy : new boolean[]{true, false}) {
            for (int count : new int[]{1, 64, 2304, 10000}) {
                SystemShopItem bulk = item(3.51, 1.17);
                bulk.pressure = buy ? 0.68 : -0.68;
                SystemShopItem singles = bulk.copy();
                long now = NOW + 3_600_000;
                DynamicShopPrice.Quote quote = DynamicShopPrice.quote(bulk, buy, count, now);
                long total = 0;
                for (int n = 0; n < count; n++) {
                    DynamicShopPrice.Quote single = DynamicShopPrice.quote(singles, buy, 1, now);
                    total += single.cents();
                    DynamicShopPrice.apply(singles, single);
                }
                assertEquals(quote.cents(), total);
                assertEquals(quote.pressure(), singles.pressure);
            }
        }
    }

    @Test void immediateRoundTripsNeverProfitEvenWithZeroSpreadAtEitherBound() {
        Random random = new Random(74919);
        for (int i = 0; i < 150; i++) {
            for (boolean firstBuy : new boolean[]{true, false}) {
                SystemShopItem item = item(0.01 + random.nextInt(100_000) / 100.0, 0);
                item.sellPrice = item.buyPrice; // Deliberately stress a zero-spread market.
                item.pricing = SystemShopPricing.defaults(item.buyPrice, item.sellPrice);
                item.pressure = switch (i % 3) {
                    case 0 -> Math.log(0.5);
                    case 1 -> Math.log(2);
                    default -> (random.nextDouble() - 0.5) * 1.3;
                };
                int count = 1 + random.nextInt(5000);
                DynamicShopPrice.Quote first = DynamicShopPrice.quote(item, firstBuy, count, NOW);
                DynamicShopPrice.apply(item, first);
                DynamicShopPrice.Quote reverse = DynamicShopPrice.quote(item, !firstBuy, count, NOW);
                long paid = firstBuy ? first.cents() : reverse.cents();
                long earned = firstBuy ? reverse.cents() : first.cents();
                assertTrue(paid >= earned, "Round-trip profit: " + (earned - paid));
            }
        }
    }

    @Test void centsAreRoundedPerUnitConservativelyNotAfterMultiplication() {
        SystemShopItem item = item(0.015, 0.015);
        item.pricing.changePercent = 0;
        assertEquals(128, DynamicShopPrice.quote(item, true, 64, NOW).cents());
        assertEquals(64, DynamicShopPrice.quote(item, false, 64, NOW).cents());
    }

    @Test void disabledSidesAndInvalidQuantitiesCannotProduceQuotes() {
        SystemShopItem item = item(0, 1);
        assertThrows(IllegalArgumentException.class, () -> DynamicShopPrice.quote(item, true, 1, NOW));
        assertTrue(DynamicShopPrice.quote(item, false, 64, NOW).cents() > 0);
        for (int count : new int[]{0, -1, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> DynamicShopPrice.quote(item, false, count, NOW));
    }

    @Test void corruptLivePressureFailsClosedInsteadOfProducingFreeItems() {
        SystemShopItem item = item(10, 3);
        for (double pressure : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            item.pressure = pressure;
            assertThrows(IllegalArgumentException.class, () -> DynamicShopPrice.quote(item, true, 1, NOW));
            assertThrows(IllegalArgumentException.class, () -> DynamicShopPrice.quote(item, false, 1, NOW));
        }
    }

    @Test void centAlignedAbsoluteBoundsRemainHardBoundsAfterRounding() {
        SystemShopItem item = item(4.57, 0.29);
        item.pricing.maxBuyPrice = 4.57;
        item.pricing.minSellPrice = 0.29;
        item.pressure = Math.log(2);
        assertEquals(457, DynamicShopPrice.quote(item, true, 1, NOW).cents());
        item.pressure = Math.log(0.5);
        assertEquals(29, DynamicShopPrice.quote(item, false, 1, NOW).cents());
    }

}
