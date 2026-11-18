package io.wifi.habitrainsurvive.redpacket;

/** Decimal-to-cent and text boundaries shared by the command layer and tests. */
public final class RedPacketValues {
    public static final double MIN_AMOUNT = 0.01;
    public static final double MAX_AMOUNT = 1_000_000_000.0;
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 100;
    public static final int MAX_MESSAGE = 64;
    public static final String DEFAULT_MESSAGE = "恭喜发财，大吉大利！";

    private RedPacketValues() {}

    public static long cents(double amount) {
        if (!Double.isFinite(amount) || amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("红包总额须为 0.01 到 10 亿金币");
        }
        long cents = Math.round(amount * 100.0);
        if (cents < 1) throw new IllegalArgumentException("红包总额至少为 0.01 金币");
        return cents;
    }

    public static int count(int count) {
        if (count < MIN_COUNT || count > MAX_COUNT) throw new IllegalArgumentException("红包份数须为 1 到 100");
        return count;
    }

    public static String message(String raw) {
        if (raw == null) return DEFAULT_MESSAGE;
        StringBuilder clean = new StringBuilder();
        raw.codePoints().filter(code -> !Character.isISOControl(code)).forEach(clean::appendCodePoint);
        String text = clean.toString().trim();
        if (text.isEmpty()) return DEFAULT_MESSAGE;
        int length = text.codePointCount(0, text.length());
        if (length > MAX_MESSAGE) {
            text = text.substring(0, text.offsetByCodePoints(0, MAX_MESSAGE));
        }
        return text;
    }
}
