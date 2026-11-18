package io.wifi.habitrainsurvive.lottery;

import java.util.Locale;

final class LotteryEditValues {
    private LotteryEditValues() {}

    static String poolId(String value) {
        String id = value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("奖池ID须为1到32位英文、数字、下划线或短横线");
        }
        return id;
    }

    static String name(String value) {
        String name = value.trim();
        if (name.isEmpty() || name.length() > 64) throw new IllegalArgumentException("名称须为1到64个字符");
        return name.replace('&', '§');
    }

    static int integer(String value, int max) {
        try {
            int number = Integer.parseInt(value.trim());
            if (number >= 1 && number <= max) return number;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("请输入1到" + max + "之间的整数");
    }

    static double coins(String value) {
        try {
            double number = Double.parseDouble(value.trim());
            if (Double.isFinite(number) && number >= 0 && number <= 1_000_000_000) return number;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("金币须为0到10亿之间的有限数字");
    }
}
