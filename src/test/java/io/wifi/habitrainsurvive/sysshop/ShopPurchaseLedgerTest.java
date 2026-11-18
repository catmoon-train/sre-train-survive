package io.wifi.habitrainsurvive.sysshop;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ShopPurchaseLedgerTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String CATEGORY = "category:building";
    private static final String ITEM = "item:00000000-0000-0000-0000-000000000003";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long NOW = time("2024-04-07T12:00:00", SHANGHAI);
    private static final Gson GSON = new Gson();
    private static final List<BiConsumer<ShopPurchaseLimits, Integer>> LIMITS = List.of(
            (p, v) -> p.perTransaction = v, (p, v) -> p.daily = v, (p, v) -> p.weekly = v,
            (p, v) -> p.monthly = v, (p, v) -> p.total = v);

    private static long time(String local, ZoneId zone) {
        return LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli();
    }

    private static ShopPurchaseLimits limits(int value) {
        ShopPurchaseLimits limits = new ShopPurchaseLimits();
        for (BiConsumer<ShopPurchaseLimits, Integer> field : LIMITS) field.accept(limits, value);
        return limits;
    }

    private static ShopPurchaseLedger.Remaining remaining(ShopPurchaseLedger ledger, long now) {
        return ledger.remaining(ALICE, ITEM, limits(10), false, now, SHANGHAI);
    }

    private static void assertReason(String reason, String... fragments) {
        assertNotNull(reason);
        for (String fragment : fragments) assertTrue(reason.contains(fragment), reason);
    }

    @Test void zeroLimitsAreUnlimitedWithoutCreatingHistory() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ShopPurchasePolicy policy = new ShopPurchasePolicy();
        assertNull(ledger.problem(ALICE, ITEM, policy, 100_000, NOW, SHANGHAI));
        assertEquals(new ShopPurchaseLedger.Remaining(-1, -1, -1, -1, -1),
                ledger.remaining(ALICE, ITEM, policy.personal, false, NOW, SHANGHAI));
        assertEquals(Long.MAX_VALUE, ledger.remaining(ALICE, ITEM, policy.global, true, NOW, SHANGHAI).maximum());
        assertTrue(ledger.personal.isEmpty());
        assertTrue(ledger.global.isEmpty());
    }

    @Test void personalPlayersAreIndependentButServerCapIsShared() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ShopPurchasePolicy policy = new ShopPurchasePolicy();
        policy.personal.daily = 5;
        policy.global.daily = 8;
        ledger.record(ALICE, List.of(ITEM), 5, NOW, SHANGHAI);
        assertReason(ledger.problem(ALICE, ITEM, policy, 1, NOW, SHANGHAI), "个人", "每日", "剩余额度：0");
        assertNull(ledger.problem(BOB, ITEM, policy, 3, NOW, SHANGHAI));
        ledger.record(BOB, List.of(ITEM), 3, NOW, SHANGHAI);
        assertEquals(2, ledger.remaining(BOB, ITEM, policy.personal, false, NOW, SHANGHAI).daily());
        assertReason(ledger.problem(BOB, ITEM, policy, 1, NOW, SHANGHAI), "全服", "每日", "剩余额度：0");
        assertEquals(8, ledger.global.get(ITEM).total);
    }

    @Test void everyPersonalAndGlobalDimensionHasUsefulChineseRejection() {
        String[] labels = {"单次", "每日", "每周", "每月", "累计"};
        for (boolean shared : new boolean[]{false, true}) {
            for (int i = 0; i < LIMITS.size(); i++) {
                ShopPurchaseLedger ledger = new ShopPurchaseLedger();
                ShopPurchasePolicy policy = new ShopPurchasePolicy();
                LIMITS.get(i).accept(shared ? policy.global : policy.personal, 4);
                ledger.record(ALICE, List.of(ITEM), 3, NOW, SHANGHAI);
                String before = GSON.toJson(ledger);
                assertReason(ledger.problem(ALICE, ITEM, policy, 5, NOW, SHANGHAI),
                        shared ? "全服" : "个人", labels[i], "剩余额度：" + (i == 0 ? 4 : 1));
                assertNull(ledger.problem(ALICE, ITEM, policy, i == 0 ? 4 : 1, NOW, SHANGHAI));
                assertEquals(before, GSON.toJson(ledger));
            }
        }
    }

    @Test void callerCombinesCategoryAndItemChecksBeforeRecordingEither() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ShopPurchasePolicy category = new ShopPurchasePolicy();
        ShopPurchasePolicy item = new ShopPurchasePolicy();
        category.global.daily = 6;
        item.personal.daily = 4;
        assertNull(ledger.problem(ALICE, CATEGORY, category, 4, NOW, SHANGHAI));
        assertNull(ledger.problem(ALICE, ITEM, item, 4, NOW, SHANGHAI));
        ledger.record(ALICE, List.of(CATEGORY, ITEM), 4, NOW, SHANGHAI);
        String before = GSON.toJson(ledger);
        assertNull(ledger.problem(ALICE, CATEGORY, category, 1, NOW, SHANGHAI));
        assertNotNull(ledger.problem(ALICE, ITEM, item, 1, NOW, SHANGHAI));
        assertEquals(before, GSON.toJson(ledger), "One failing policy means caller records nothing");
        ledger.record(BOB, List.of(CATEGORY, "item:other"), 2, NOW, SHANGHAI);
        assertReason(ledger.problem(ALICE, CATEGORY, category, 1, NOW, SHANGHAI), "全服", "每日");
        assertEquals(4, ledger.global.get(ITEM).total);
    }

    @Test void successivePurchasesConsumeAllCountsButNotPerTransactionAllowance() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(ITEM), 2, NOW, SHANGHAI);
        ledger.record(ALICE, List.of(ITEM), 3, NOW, SHANGHAI);
        assertEquals(new ShopPurchaseLedger.Remaining(10, 5, 5, 5, 5), remaining(ledger, NOW));
        assertEquals(5, ledger.personal.get(ALICE).get(ITEM).total);
        assertEquals(5, ledger.global.get(ITEM).total);
    }

    @Test void refusalsUnpaidBuysAndSellingDoNotChangePurchaseHistory() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ShopPurchasePolicy policy = new ShopPurchasePolicy();
        policy.personal.total = 2;
        ledger.record(ALICE, List.of(ITEM), 2, NOW, SHANGHAI);
        String before = GSON.toJson(ledger);
        for (int i = 0; i < 3; i++) assertNotNull(ledger.problem(ALICE, ITEM, policy, 1, NOW, SHANGHAI));
        // A sell has no ledger API; a failed payment/delivery never invokes record either.
        assertNull(ledger.problem(BOB, ITEM, policy, 1, NOW, SHANGHAI));
        assertEquals(before, GSON.toJson(ledger));
        assertEquals(0, ledger.remaining(ALICE, ITEM, policy.personal, false, NOW, SHANGHAI).total());
    }

    @Test void rolloverPreviewsAndFailedBuysNeverResetStoredUsage() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(ITEM), 3, NOW, SHANGHAI);
        String before = GSON.toJson(ledger);
        long nextMonth = time("2024-05-01T00:00:00", SHANGHAI);
        assertEquals(new ShopPurchaseLedger.Remaining(10, 10, 10, 10, 7), remaining(ledger, nextMonth));
        ShopPurchasePolicy policy = new ShopPurchasePolicy();
        policy.personal.total = 3;
        assertNotNull(ledger.problem(ALICE, ITEM, policy, 1, nextMonth, SHANGHAI));
        assertEquals(before, GSON.toJson(ledger));
        assertEquals(7, remaining(ledger, NOW).daily());
    }

    @Test void resetClearsOnlyTheSpecifiedKeyAcrossPlayersAndGlobal() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(CATEGORY, ITEM), 3, NOW, SHANGHAI);
        ledger.record(BOB, List.of(CATEGORY, ITEM), 2, NOW, SHANGHAI);
        ledger.reset(CATEGORY);
        assertFalse(ledger.global.containsKey(CATEGORY));
        assertFalse(ledger.personal.get(ALICE).containsKey(CATEGORY));
        assertFalse(ledger.personal.get(BOB).containsKey(CATEGORY));
        assertEquals(5, ledger.global.get(ITEM).total);
        assertEquals(3, ledger.personal.get(ALICE).get(ITEM).total);
        assertEquals(2, ledger.personal.get(BOB).get(ITEM).total);
        ledger.reset("category:missing");
        assertEquals(7, remaining(ledger, NOW).total());
    }

    @Test void localWeekStartsMondayNotSundayAndEveryWeekdayUsesSameKey() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        long monday = time("2024-04-01T00:00:00", SHANGHAI);
        for (int day = 0; day < 7; day++) {
            long now = monday + day * 86_400_000L;
            ledger.record(ALICE, List.of(ITEM), 1, now, SHANGHAI);
            assertEquals(9 - day, remaining(ledger, now).weekly());
            assertEquals(LocalDate.of(2024, 4, 1).toEpochDay(), ledger.global.get(ITEM).weekKey);
        }
        long nextMonday = time("2024-04-08T00:00:00", SHANGHAI);
        assertEquals(3, remaining(ledger, nextMonday - 1).weekly());
        assertEquals(10, remaining(ledger, nextMonday).weekly());
        ledger.record(ALICE, List.of(ITEM), 2, nextMonday, SHANGHAI);
        assertEquals(8, remaining(ledger, nextMonday).weekly());
        assertEquals(1, remaining(ledger, nextMonday).total());
    }

    @Test void isoYearEdgeDoesNotResetWeekOnJanuaryFirst() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(ITEM), 3, time("2020-12-28T00:00:00", SHANGHAI), SHANGHAI);
        long january = time("2021-01-01T00:00:00", SHANGHAI);
        assertEquals(7, remaining(ledger, january).weekly());
        assertEquals(10, remaining(ledger, january).monthly());
        assertEquals(7, remaining(ledger, time("2021-01-03T23:59:59", SHANGHAI)).weekly());
        assertEquals(10, remaining(ledger, time("2021-01-04T00:00:00", SHANGHAI)).weekly());
    }

    @Test void leapMonthBoundaryResetsOnlyElapsedPeriodsAndNeverTotal() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        long february = time("2024-02-29T23:59:59.999", SHANGHAI);
        long march = time("2024-03-01T00:00:00", SHANGHAI);
        ledger.record(ALICE, List.of(ITEM), 3, february, SHANGHAI);
        assertEquals(new ShopPurchaseLedger.Remaining(10, 10, 7, 10, 7), remaining(ledger, march));
        ledger.record(ALICE, List.of(ITEM), 2, march, SHANGHAI);
        assertEquals(new ShopPurchaseLedger.Remaining(10, 8, 5, 8, 5), remaining(ledger, march));
        assertEquals(2024L * 12 + 2, ledger.global.get(ITEM).monthKey);
    }

    @Test void midnightUsesSuppliedTimezoneRatherThanUtcOrMachineDefault() {
        ZoneId utc = ZoneId.of("UTC");
        long before = Instant.parse("2024-04-07T15:59:59.999Z").toEpochMilli();
        long after = before + 1;
        ShopPurchaseLedger localLedger = new ShopPurchaseLedger();
        ShopPurchaseLedger utcLedger = new ShopPurchaseLedger();
        localLedger.record(ALICE, List.of(ITEM), 3, before, SHANGHAI);
        utcLedger.record(ALICE, List.of(ITEM), 3, before, utc);
        assertEquals(10, localLedger.remaining(ALICE, ITEM, limits(10), false, after, SHANGHAI).daily());
        assertEquals(10, localLedger.remaining(ALICE, ITEM, limits(10), false, after, SHANGHAI).weekly());
        assertEquals(7, utcLedger.remaining(ALICE, ITEM, limits(10), false, after, utc).daily());
        assertEquals(7, utcLedger.remaining(ALICE, ITEM, limits(10), false, after, utc).weekly());
    }

    @Test void springDstDayHasTwentyThreeHoursButResetsAtLocalMidnight() {
        ZoneId zone = ZoneId.of("America/New_York");
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        long start = time("2024-03-10T00:00:00", zone);
        long nextDay = time("2024-03-11T00:00:00", zone);
        assertEquals(23 * 3_600_000L, nextDay - start);
        ledger.record(ALICE, List.of(ITEM), 3, start, zone);
        assertEquals(7, ledger.remaining(ALICE, ITEM, limits(10), false, nextDay - 1, zone).daily());
        assertEquals(10, ledger.remaining(ALICE, ITEM, limits(10), false, nextDay, zone).daily());
        assertEquals(10, ledger.remaining(ALICE, ITEM, limits(10), false, nextDay, zone).weekly());
    }

    @Test void fallDstRepeatedHourIsNotANewDayAndDayLastsTwentyFiveHours() {
        ZoneId zone = ZoneId.of("America/New_York");
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        long start = time("2024-11-03T00:00:00", zone);
        long nextDay = time("2024-11-04T00:00:00", zone);
        assertEquals(25 * 3_600_000L, nextDay - start);
        ledger.record(ALICE, List.of(ITEM), 3, Instant.parse("2024-11-03T05:30:00Z").toEpochMilli(), zone);
        assertEquals(7, ledger.remaining(ALICE, ITEM, limits(10), false,
                Instant.parse("2024-11-03T06:30:00Z").toEpochMilli(), zone).daily());
        assertEquals(7, ledger.remaining(ALICE, ITEM, limits(10), false, nextDay - 1, zone).daily());
        assertEquals(10, ledger.remaining(ALICE, ITEM, limits(10), false, nextDay, zone).daily());
    }

    @Test void backwardClockNeverResetsUsageOrMovesCheckpointsBackwards() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        long future = time("2025-05-05T12:00:00", SHANGHAI);
        ledger.record(ALICE, List.of(ITEM), 3, future, SHANGHAI);
        ShopPurchaseLedger.Usage checkpoint = ledger.global.get(ITEM);
        assertEquals(new ShopPurchaseLedger.Remaining(10, 7, 7, 7, 7), remaining(ledger, NOW));
        ShopPurchasePolicy policy = new ShopPurchasePolicy();
        policy.personal.daily = 3;
        assertNotNull(ledger.problem(ALICE, ITEM, policy, 1, NOW, SHANGHAI));
        ledger.record(ALICE, List.of(ITEM), 2, NOW, SHANGHAI);
        assertEquals(new ShopPurchaseLedger.Remaining(10, 5, 5, 5, 5), remaining(ledger, future));
        assertEquals(checkpoint.dayKey, ledger.global.get(ITEM).dayKey);
        assertEquals(checkpoint.weekKey, ledger.global.get(ITEM).weekKey);
        assertEquals(checkpoint.monthKey, ledger.global.get(ITEM).monthKey);
        assertEquals(10, remaining(ledger, time("2025-06-02T00:00:00", SHANGHAI)).daily());
        assertEquals(5, remaining(ledger, time("2025-06-02T00:00:00", SHANGHAI)).total());
    }

    @Test void gsonRestartPreservesEveryScopeAndPlayerAndAllowsFurtherRecording() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(CATEGORY, ITEM), 3, NOW, SHANGHAI);
        ledger.record(BOB, List.of(CATEGORY), 2, NOW, SHANGHAI);
        String json = GSON.toJson(ledger);
        ShopPurchaseLedger restored = GSON.fromJson(json, ShopPurchaseLedger.class);
        restored.migrate();
        assertEquals(json, GSON.toJson(restored));
        assertEquals(remaining(ledger, NOW), remaining(restored, NOW));
        assertEquals(5, restored.global.get(CATEGORY).total);
        assertEquals(2, restored.personal.get(BOB).get(CATEGORY).total);
        restored.record(ALICE, List.of(CATEGORY, ITEM), 1, NOW, SHANGHAI);
        assertEquals(4, restored.personal.get(ALICE).get(ITEM).total);
        assertEquals(6, restored.global.get(CATEGORY).total);
        assertEquals(3, ledger.global.get(ITEM).total);
    }

    @Test void migrationInitializesMissingAndNullMapsIncludingNestedPlayerMaps() {
        for (String json : new String[]{"{}", "{\"global\":null,\"personal\":null}"}) {
            ShopPurchaseLedger ledger = GSON.fromJson(json, ShopPurchaseLedger.class);
            ledger.migrate();
            assertNotNull(ledger.personal);
            assertNotNull(ledger.global);
            ledger.record(ALICE, List.of(ITEM), 1, NOW, SHANGHAI);
            assertEquals(1, ledger.global.get(ITEM).total);
        }
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(ITEM), 3, NOW, SHANGHAI);
        ShopPurchaseLedger.Usage history = ledger.global.get(ITEM);
        ledger.personal.put(BOB, null);
        ledger.migrate();
        assertTrue(ledger.personal.get(BOB).isEmpty());
        assertSame(history, ledger.global.get(ITEM));
        assertEquals(3, history.total);
    }

    @Test void readOnlyChecksDoNotInitializeEvenNullLegacyMaps() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.global = null;
        ledger.personal = null;
        assertNull(ledger.problem(ALICE, ITEM, new ShopPurchasePolicy(), 1, NOW, SHANGHAI));
        assertEquals(10, remaining(ledger, NOW).daily());
        assertNull(ledger.global);
        assertNull(ledger.personal);
    }

    @Test void migrationRejectsCorruptUsageWithoutDiscardingOrReplacingHistory() {
        List<Consumer<ShopPurchaseLedger.Usage>> corruptions = List.of(
                u -> u.daily = -1, u -> u.weekly = Long.MIN_VALUE, u -> u.monthly = -10,
                u -> u.total = -1, u -> u.dayKey = Long.MAX_VALUE,
                u -> u.weekKey++, u -> u.monthKey = Long.MAX_VALUE, u -> u.dayKey = Long.MIN_VALUE);
        for (boolean shared : new boolean[]{false, true}) {
            for (Consumer<ShopPurchaseLedger.Usage> corrupt : corruptions) {
                ShopPurchaseLedger ledger = new ShopPurchaseLedger();
                ledger.record(ALICE, List.of(ITEM), 1, NOW, SHANGHAI);
                ShopPurchaseLedger.Usage usage = shared ? ledger.global.get(ITEM) : ledger.personal.get(ALICE).get(ITEM);
                corrupt.accept(usage);
                String before = GSON.toJson(ledger);
                assertThrows(IllegalArgumentException.class, ledger::migrate);
                assertEquals(before, GSON.toJson(ledger));
                assertSame(usage, shared ? ledger.global.get(ITEM) : ledger.personal.get(ALICE).get(ITEM));
            }
        }
    }

    @Test void nullUsageIsNotTreatedAsMissingHistoryEvenDuringRollover() {
        for (boolean shared : new boolean[]{false, true}) {
            ShopPurchaseLedger ledger = new ShopPurchaseLedger();
            ledger.record(ALICE, List.of(ITEM), 1, NOW, SHANGHAI);
            (shared ? ledger.global : ledger.personal.get(ALICE)).put(ITEM, null);
            assertThrows(IllegalArgumentException.class, ledger::migrate);
            assertThrows(IllegalArgumentException.class,
                    () -> ledger.problem(ALICE, ITEM, new ShopPurchasePolicy(), 1, NOW + 86_400_000L, SHANGHAI));
            String before = GSON.toJson(ledger);
            assertThrows(IllegalArgumentException.class,
                    () -> ledger.record(ALICE, List.of(CATEGORY, ITEM), 1, NOW, SHANGHAI));
            assertEquals(before, GSON.toJson(ledger));
        }
    }

    @Test void recordDeduplicatesKeysAndKeepsUnlimitedHistoryForFuturePolicies() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(ITEM, CATEGORY, ITEM, CATEGORY), 3, NOW, SHANGHAI);
        assertEquals(2, ledger.global.size());
        assertEquals(3, ledger.global.get(ITEM).total);
        assertEquals(3, ledger.global.get(CATEGORY).total);
        assertEquals(3, ledger.personal.get(ALICE).get(ITEM).daily);
        ShopPurchasePolicy newlyEnabled = new ShopPurchasePolicy();
        newlyEnabled.global.daily = 3;
        assertReason(ledger.problem(BOB, ITEM, newlyEnabled, 1, NOW, SHANGHAI), "全服", "每日", "剩余额度：0");
        newlyEnabled.personal.total = 3;
        assertReason(ledger.problem(ALICE, ITEM, newlyEnabled, 1, NOW, SHANGHAI), "个人", "累计", "剩余额度：0");
        String before = GSON.toJson(ledger);
        ledger.record(ALICE, List.of(), 1, NOW, SHANGHAI);
        assertEquals(before, GSON.toJson(ledger));
    }

    @Test void overflowChecksEveryCounterAndScopeAndRecordHasNoPartialWrites() {
        List<Consumer<ShopPurchaseLedger.Usage>> fullCounters = List.of(
                u -> u.daily = Long.MAX_VALUE, u -> u.weekly = Long.MAX_VALUE,
                u -> u.monthly = Long.MAX_VALUE, u -> u.total = Long.MAX_VALUE);
        String[] labels = {"每日", "每周", "每月", "累计"};
        for (boolean shared : new boolean[]{false, true}) {
            for (int i = 0; i < fullCounters.size(); i++) {
                ShopPurchaseLedger ledger = new ShopPurchaseLedger();
                ledger.record(ALICE, List.of(ITEM, CATEGORY), 1, NOW, SHANGHAI);
                ShopPurchaseLedger.Usage full = shared ? ledger.global.get(ITEM) : ledger.personal.get(ALICE).get(ITEM);
                fullCounters.get(i).accept(full);
                String before = GSON.toJson(ledger);
                assertReason(ledger.problem(ALICE, ITEM, new ShopPurchasePolicy(), 1, NOW, SHANGHAI),
                        shared ? "全服" : "个人", labels[i], "溢出", "剩余额度：0");
                assertThrows(ArithmeticException.class,
                        () -> ledger.record(ALICE, List.of(CATEGORY, ITEM), 1, NOW, SHANGHAI));
                assertEquals(before, GSON.toJson(ledger));
                assertSame(full, shared ? ledger.global.get(ITEM) : ledger.personal.get(ALICE).get(ITEM));
                assertEquals(1, ledger.global.get(CATEGORY).total);
            }
        }
    }

    @Test void exactLongBoundaryIsAllowedButOneMoreIsRejected() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(ITEM), 1, NOW, SHANGHAI);
        ledger.global.get(ITEM).total = Long.MAX_VALUE - 2;
        assertNull(ledger.problem(ALICE, ITEM, new ShopPurchasePolicy(), 2, NOW, SHANGHAI));
        assertReason(ledger.problem(ALICE, ITEM, new ShopPurchasePolicy(), 3, NOW, SHANGHAI), "全服", "累计", "剩余额度：2");
        ledger.record(ALICE, List.of(ITEM), 2, NOW, SHANGHAI);
        assertEquals(Long.MAX_VALUE, ledger.global.get(ITEM).total);
        assertNotNull(ledger.problem(ALICE, ITEM, new ShopPurchasePolicy(), 1, NOW, SHANGHAI));
    }

    @Test void expiredPeriodCounterCanResetButTotalOverflowNeverExpires() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(ITEM), 1, NOW, SHANGHAI);
        ledger.global.get(ITEM).daily = Long.MAX_VALUE;
        long tomorrow = time("2024-04-08T00:00:00", SHANGHAI);
        assertNull(ledger.problem(ALICE, ITEM, new ShopPurchasePolicy(), 1, tomorrow, SHANGHAI));
        assertEquals(Long.MAX_VALUE, ledger.global.get(ITEM).daily);
        ledger.record(ALICE, List.of(ITEM), 1, tomorrow, SHANGHAI);
        assertEquals(1, ledger.global.get(ITEM).daily);
        ledger.global.get(ITEM).total = Long.MAX_VALUE;
        assertReason(ledger.problem(ALICE, ITEM, new ShopPurchasePolicy(), 1,
                time("2030-01-01T00:00:00", SHANGHAI), SHANGHAI), "累计", "溢出");
    }

    @Test void invalidQuantitiesKeysAndSettingsCannotPartiallyRecord() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        for (int invalid : new int[]{0, -1, 100_001, Integer.MAX_VALUE}) {
            assertNotNull(ledger.problem(ALICE, ITEM, new ShopPurchasePolicy(), invalid, NOW, SHANGHAI));
            assertThrows(IllegalArgumentException.class,
                    () -> ledger.record(ALICE, List.of(ITEM), invalid, NOW, SHANGHAI));
        }
        for (List<String> keys : List.of(List.of(ITEM, " "), Arrays.asList(ITEM, null))) {
            assertThrows(IllegalArgumentException.class, () -> ledger.record(ALICE, keys, 1, NOW, SHANGHAI));
        }
        ShopPurchasePolicy invalidPolicy = new ShopPurchasePolicy();
        invalidPolicy.global.total = -1;
        assertThrows(IllegalArgumentException.class, () -> ledger.problem(ALICE, ITEM, invalidPolicy, 1, NOW, SHANGHAI));
        assertThrows(IllegalArgumentException.class, () -> ledger.record(null, List.of(ITEM), 1, NOW, SHANGHAI));
        assertThrows(IllegalArgumentException.class, () -> ledger.record(ALICE, null, 1, NOW, SHANGHAI));
        assertThrows(NullPointerException.class, () -> ledger.record(ALICE, List.of(ITEM), 1, NOW, null));
        assertTrue(ledger.global.isEmpty());
        assertTrue(ledger.personal.isEmpty());
    }

    @Test void maximumIgnoresUnlimitedButPreservesZeroAndLongSizedValues() {
        assertEquals(Long.MAX_VALUE, new ShopPurchaseLedger.Remaining(-1, -1, -1, -1, -1).maximum());
        assertEquals(4, new ShopPurchaseLedger.Remaining(-1, 9, 4, -1, 6).maximum());
        assertEquals(0, new ShopPurchaseLedger.Remaining(7, -1, 0, 8, 3).maximum());
        assertEquals(3_000_000_000L,
                new ShopPurchaseLedger.Remaining(-1, 4_000_000_000L, -1, -1, 3_000_000_000L).maximum());
    }

    @Test void loweredLimitsClampToZeroInsteadOfNegativeUnlimitedSentinel() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        ledger.record(ALICE, List.of(ITEM), 20, NOW, SHANGHAI);
        assertEquals(new ShopPurchaseLedger.Remaining(10, 0, 0, 0, 0), remaining(ledger, NOW));
        assertEquals(0, remaining(ledger, NOW).maximum());
    }

    @Test void preEpochDatesUseRealPeriodKeysNotZeroAsAnUninitializedMarker() {
        ShopPurchaseLedger ledger = new ShopPurchaseLedger();
        long beforeEpoch = time("1969-12-31T23:59:59", SHANGHAI);
        long newYear = time("1970-01-01T00:00:00", SHANGHAI);
        ledger.record(ALICE, List.of(ITEM), 3, beforeEpoch, SHANGHAI);
        assertEquals(-1, ledger.global.get(ITEM).dayKey);
        assertEquals(new ShopPurchaseLedger.Remaining(10, 10, 7, 10, 7), remaining(ledger, newYear));
        ledger.migrate();
    }
}
