package io.wifi.habitrainsurvive.sysshop;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pure purchase history. The caller must lock check/payment/delivery/record as one transaction. */
public class ShopPurchaseLedger implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final int MAX_COUNT = 100_000;

    // Public for Gson persistence. Runtime changes should go through the synchronized methods.
    public Map<String, Usage> global = new HashMap<>();
    public Map<UUID, Map<String, Usage>> personal = new HashMap<>();

    public ShopPurchaseLedger() { }

    public static class Usage implements Serializable {
        private static final long serialVersionUID = 1L;
        public long dayKey = Long.MIN_VALUE;
        public long weekKey = Long.MIN_VALUE;
        public long monthKey = Long.MIN_VALUE;
        public long daily;
        public long weekly;
        public long monthly;
        public long total;

        public Usage() { }

        private void validate() {
            if (daily < 0 || weekly < 0 || monthly < 0 || total < 0)
                throw new IllegalArgumentException("购买历史计数无效（负数或溢出），请管理员检查");
            validatePeriod(dayKey, daily, LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay());
            validatePeriod(weekKey, weekly, LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay());
            validatePeriod(monthKey, monthly, (long) LocalDate.MIN.getYear() * 12,
                    (long) LocalDate.MAX.getYear() * 12 + 11);
            if (weekKey != Long.MIN_VALUE && Math.floorMod(weekKey + 3, 7) != 0)
                throw new IllegalArgumentException("购买历史周起点不是周一");
        }

        private static void validatePeriod(long key, long count, long min, long max) {
            if (key == Long.MIN_VALUE ? count != 0 : key < min || key > max)
                throw new IllegalArgumentException("购买历史周期无效，请管理员检查");
        }

        private Usage copy() {
            validate();
            Usage copy = new Usage();
            copy.dayKey = dayKey;
            copy.weekKey = weekKey;
            copy.monthKey = monthKey;
            copy.daily = daily;
            copy.weekly = weekly;
            copy.monthly = monthly;
            copy.total = total;
            return copy;
        }

        private Usage increment(int count, Period period) {
            Usage next = copy();
            next.daily = Math.addExact(used(daily, dayKey, period.day), count);
            next.weekly = Math.addExact(used(weekly, weekKey, period.week), count);
            next.monthly = Math.addExact(used(monthly, monthKey, period.month), count);
            next.total = Math.addExact(total, count);
            next.dayKey = Math.max(dayKey, period.day);
            next.weekKey = Math.max(weekKey, period.week);
            next.monthKey = Math.max(monthKey, period.month);
            return next;
        }
    }

    public record Remaining(long perTransaction, long daily, long weekly, long monthly, long total) {
        /** Unlimited dimensions do not constrain the maximum. */
        public long maximum() {
            long maximum = Long.MAX_VALUE;
            for (long value : new long[]{perTransaction, daily, weekly, monthly, total})
                if (value >= 0) maximum = Math.min(maximum, value);
            return maximum;
        }
    }

    private record Period(long day, long week, long month) {
        private static Period at(long epochMillis, ZoneId zone) {
            LocalDate date = Instant.ofEpochMilli(epochMillis).atZone(Objects.requireNonNull(zone)).toLocalDate();
            long day = date.toEpochDay();
            return new Period(day, day - date.getDayOfWeek().getValue() + 1,
                    (long) date.getYear() * 12 + date.getMonthValue() - 1);
        }
    }

    /** Missing maps are additive schema migration; corrupt entries must never erase history. */
    public synchronized void migrate() {
        validateMap(global);
        if (personal != null) {
            for (Map.Entry<UUID, Map<String, Usage>> entry : personal.entrySet()) {
                requirePlayer(entry.getKey());
                validateMap(entry.getValue());
            }
        }
        if (global == null) global = new HashMap<>();
        if (personal == null) personal = new HashMap<>();
        personal.replaceAll((player, scopes) -> scopes == null ? new HashMap<>() : scopes);
    }

    private static void validateMap(Map<String, Usage> scopes) {
        if (scopes == null) return;
        for (Map.Entry<String, Usage> entry : scopes.entrySet()) {
            requireKey(entry.getKey());
            if (entry.getValue() == null) throw new IllegalArgumentException("购买历史不能为空");
            entry.getValue().validate();
        }
    }

    /** Read-only, including on a new day or after clock rollback. No migration or lazy map writes. */
    public synchronized Remaining remaining(UUID player, String key, ShopPurchaseLimits limits,
                                             boolean shared, long epochMillis, ZoneId zone) {
        requirePlayer(player);
        requireKey(key);
        if (limits == null) throw new IllegalArgumentException("购买限额不能为空");
        limits.validate();
        return remaining(usage(player, key, shared), limits, Period.at(epochMillis, zone));
    }

    private static Remaining remaining(Usage usage, ShopPurchaseLimits limits, Period period) {
        return new Remaining(left(limits.perTransaction, 0),
                left(limits.daily, used(usage.daily, usage.dayKey, period.day)),
                left(limits.weekly, used(usage.weekly, usage.weekKey, period.week)),
                left(limits.monthly, used(usage.monthly, usage.monthKey, period.month)),
                left(limits.total, usage.total));
    }

    private static long left(int limit, long used) {
        return limit == 0 ? -1 : Math.max(0L, (long) limit - used);
    }

    private static long used(long count, long storedKey, long currentKey) {
        return currentKey > storedKey ? 0 : count;
    }

    /** Invalid quantities return a reason; corrupt settings/history throw rather than fail open. */
    public synchronized String problem(UUID player, String key, ShopPurchasePolicy policy, int count,
                                       long epochMillis, ZoneId zone) {
        requirePlayer(player);
        requireKey(key);
        if (policy == null) throw new IllegalArgumentException("购买策略不能为空");
        policy.validate();
        if (count < 1 || count > MAX_COUNT) return "购买数量必须为 1..100000";
        Period period = Period.at(epochMillis, zone);
        String personalProblem = problem("个人", usage(player, key, false), policy.personal, count, period);
        if (personalProblem != null) return personalProblem;
        return problem("全服", usage(player, key, true), policy.global, count, period);
    }

    private static String problem(String scope, Usage usage, ShopPurchaseLimits limits, int count, Period period) {
        String reason = violation(scope, remaining(usage, limits, period), count, "购买限额不足");
        if (reason != null) return reason;
        // Even unlimited policies must leave room for every counter written by record().
        Remaining capacity = new Remaining(-1,
                Long.MAX_VALUE - used(usage.daily, usage.dayKey, period.day),
                Long.MAX_VALUE - used(usage.weekly, usage.weekKey, period.week),
                Long.MAX_VALUE - used(usage.monthly, usage.monthKey, period.month),
                Long.MAX_VALUE - usage.total);
        return violation(scope, capacity, count, "购买计数将溢出");
    }

    private static String violation(String scope, Remaining remaining, int count, String description) {
        long[] amounts = {remaining.perTransaction, remaining.daily, remaining.weekly, remaining.monthly, remaining.total};
        String[] periods = {"单次", "每日", "每周", "每月", "累计"};
        for (int i = 0; i < amounts.length; i++) {
            if (amounts[i] >= 0 && count > amounts[i])
                return scope + periods[i] + description + "，剩余额度：" + amounts[i];
        }
        return null;
    }

    /** Call only after ALL item/category policies passed and payment/delivery succeeded. */
    public synchronized void record(UUID player, List<String> keys, int count, long epochMillis, ZoneId zone) {
        requirePlayer(player);
        if (keys == null) throw new IllegalArgumentException("购买范围不能为空");
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("购买数量必须为 1..100000");
        Period period = Period.at(epochMillis, zone);
        LinkedHashSet<String> distinct = new LinkedHashSet<>(keys);
        for (String key : distinct) requireKey(key);

        Map<String, Usage> globalUpdates = new HashMap<>();
        Map<String, Usage> personalUpdates = new HashMap<>();
        // Prepare everything before any persisted state changes, including all overflow checks.
        for (String key : distinct) {
            personalUpdates.put(key, usage(player, key, false).increment(count, period));
            globalUpdates.put(key, usage(player, key, true).increment(count, period));
        }
        if (distinct.isEmpty()) return;
        Map<String, Usage> nextGlobal = global == null ? new HashMap<>() : new HashMap<>(global);
        Map<UUID, Map<String, Usage>> nextPersonal = personal == null ? new HashMap<>() : new HashMap<>(personal);
        Map<String, Usage> previous = nextPersonal.get(player);
        Map<String, Usage> nextPlayer = previous == null ? new HashMap<>() : new HashMap<>(previous);
        nextGlobal.putAll(globalUpdates);
        nextPlayer.putAll(personalUpdates);
        nextPersonal.put(player, nextPlayer);
        global = nextGlobal;
        personal = nextPersonal;
    }

    /** Administrative reset of this stable item/category key only, across all players. */
    public synchronized void reset(String key) {
        requireKey(key);
        if (global != null) global.remove(key);
        if (personal != null) {
            for (Map<String, Usage> scopes : personal.values())
                if (scopes != null) scopes.remove(key);
        }
    }

    private Usage usage(UUID player, String key, boolean shared) {
        Map<String, Usage> scopes = shared ? global : personal == null ? null : personal.get(player);
        if (scopes == null || !scopes.containsKey(key)) return new Usage();
        Usage usage = scopes.get(key);
        if (usage == null) throw new IllegalArgumentException("购买历史不能为空");
        usage.validate();
        return usage;
    }

    private static void requirePlayer(UUID player) {
        if (player == null) throw new IllegalArgumentException("玩家 UUID 不能为空");
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("购买范围键不能为空");
    }
}
