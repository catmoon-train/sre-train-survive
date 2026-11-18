package io.wifi.habitrainsurvive.economy;

import net.minecraft.server.level.ServerPlayer;

/** All checks happen before mutation. The caller must keep this inside its synchronized transaction. */
public final class ExperiencePayment {
    private ExperiencePayment() { }
    public static ExperienceMath.State state(ServerPlayer player) {
        long computed = ExperienceMath.totalFor(player.experienceLevel, player.experienceProgress);
        long declared = player.totalExperience;
        // Vanilla keeps totalExperience as the exact point counter. Fall back when a plugin/old save left it inconsistent.
        long total = declared >= 0 && Math.abs(declared - computed) <= ExperienceMath.nextLevelCost(player.experienceLevel) ? declared : computed;
        return new ExperienceMath.State(player.experienceLevel, (double) player.experienceProgress, total);
    }
    public static String problem(ServerPlayer player, ExperienceCost cost, int multiplier) {
        if (cost == null || !cost.enabled()) return "未设置经验价格";
        int amount;
        try { amount = cost.scaledAmount(multiplier); }
        catch (IllegalArgumentException e) { return e.getMessage(); }
        ExperienceMath.State state = state(player);
        if (cost.levels()) {
            return state.level() >= amount ? null : "经验等级不足：需要 " + amount + " 级，当前 " + state.level() + " 级";
        }
        return state.total() >= amount ? null : "经验点不足：需要 " + amount + " 点，当前 " + state.total() + " 点";
    }
    /** Returns false without mutation when the player no longer has the checked amount. */
    public static synchronized boolean pay(ServerPlayer player, ExperienceCost cost, int multiplier) {
        if (problem(player, cost, multiplier) != null) return false;
        int amount = cost.scaledAmount(multiplier);
        ExperienceMath.State old = state(player);
        ExperienceMath.State next;
        if (cost.levels()) {
            if (old.level() < amount) return false;
            next = ExperienceMath.payLevels(old, amount);
        } else {
            if (old.total() < amount) return false;
            next = ExperienceMath.payPoints(old, amount);
        }
        if (next.total() < 0 || next.total() > Integer.MAX_VALUE) return false;
        player.totalExperience = (int) next.total();
        player.experienceProgress = (float) next.progress();
        // ServerPlayer resets its client-sync cache; vanilla sends the authoritative values on the next tick.
        player.setExperienceLevels(next.level());
        player.experienceProgress = (float) next.progress();
        return true;
    }
    public static String summary(ServerPlayer player) {
        ExperienceMath.State state = state(player);
        return "等级 " + state.level() + " · 总经验 " + state.total() + " 点";
    }
}
