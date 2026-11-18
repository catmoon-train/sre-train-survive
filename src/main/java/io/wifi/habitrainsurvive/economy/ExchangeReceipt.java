package io.wifi.habitrainsurvive.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Durable receipt stored in the same atomic snapshot as the balance. Never prune without coordinator agreement. */
public record ExchangeReceipt(UUID playerUuid, long deltaCents, long balanceCents) {
    public static final long MAX_DELTA_CENTS = 100_000_000_000L;
    public static final long MAX_BALANCE_CENTS = 100_000_000_000_000L;

    public static long cents(double balance) {
        if (!Double.isFinite(balance) || balance < 0) throw new IllegalArgumentException("账户余额无效");
        return BigDecimal.valueOf(balance).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static void validate(String requestId, long deltaCents) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_.:-]{1,160}")) {
            throw new IllegalArgumentException("requestId 必须为1到160位字母、数字或 _ . : -");
        }
        if (deltaCents == 0 || deltaCents < -MAX_DELTA_CENTS || deltaCents > MAX_DELTA_CENTS) {
            throw new IllegalArgumentException("deltaCents 必须非零且绝对值不超过 " + MAX_DELTA_CENTS);
        }
    }

    public static ExchangeReceipt create(UUID uuid, double balance, long deltaCents) {
        long next = Math.addExact(cents(balance), deltaCents);
        if (next < 0) throw new ExchangeConflict("生存金币余额不足 (insufficient survival coins)");
        if (next > MAX_BALANCE_CENTS) throw new ExchangeConflict("生存金币余额超过上限");
        return new ExchangeReceipt(uuid, deltaCents, next);
    }

    public void check(UUID uuid, long delta) {
        if (!playerUuid.equals(uuid) || deltaCents != delta) throw new ExchangeConflict("交易编号已用于不同的请求");
    }

    public static final class ExchangeConflict extends IllegalArgumentException {
        public ExchangeConflict(String message) { super(message); }
    }
}
