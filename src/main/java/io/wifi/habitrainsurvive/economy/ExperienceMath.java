package io.wifi.habitrainsurvive.economy;

/** Pure vanilla experience curve. Uses level + progress as truth, not a possibly stale total counter. */
public final class ExperienceMath {
    public static final int MAX_LEVEL = 100_000;
    private ExperienceMath() { }
    public static int nextLevelCost(int level) {
        if (level < 0) throw new IllegalArgumentException("经验等级不能为负数");
        if (level >= 30) return 112 + (level - 30) * 9;
        if (level >= 15) return 37 + (level - 15) * 5;
        return 7 + level * 2;
    }
    public static long totalForLevel(int level) {
        if (level < 0 || level > MAX_LEVEL) throw new IllegalArgumentException("经验等级超出范围");
        long total = 0;
        for (int i = 0; i < level; i++) total += nextLevelCost(i);
        return total;
    }
    public static long totalFor(int level, double progress) {
        if (!Double.isFinite(progress) || progress < 0 || progress > 1) throw new IllegalArgumentException("经验进度无效");
        return totalForLevel(level) + Math.round(progress * nextLevelCost(level));
    }
    public record State(int level, double progress, long total) { }
    public static State fromTotal(long total) {
        if (total < 0) throw new IllegalArgumentException("经验点不能为负数");
        long remaining = total;
        int level = 0;
        while (remaining >= nextLevelCost(level)) {
            remaining -= nextLevelCost(level);
            level++;
            if (level > MAX_LEVEL) throw new IllegalArgumentException("经验点超出范围");
        }
        double progress = nextLevelCost(level) == 0 ? 0 : (double) remaining / nextLevelCost(level);
        return new State(level, progress, total);
    }


    public static State payLevels(State old, int levels) {
        if (old == null || levels < 1 || levels > old.level()) throw new IllegalArgumentException("经验等级不足");
        int level = old.level() - levels;
        return new State(level, old.progress(), totalFor(level, old.progress()));
    }
    public static State payPoints(State old, int points) {
        if (old == null || points < 1 || points > old.total()) throw new IllegalArgumentException("经验点不足");
        return fromTotal(old.total() - points);
    }
}
