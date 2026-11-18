package io.wifi.habitrainsurvive.sysshop;

import java.util.Locale;

/** Shared pure validation for command and GUI edits. */
public final class ShopEditValues {
    private ShopEditValues() { }

    public static String id(String text) {
        String id = text.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("分类 ID 仅允许 1..32 个小写字母、数字、_、-");
        return id;
    }

    public static String name(String text) {
        String name = text.trim().replace('&', '§');
        if (name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("名称长度必须为 1..64，不能包含控制字符");
        return name;
    }
}
