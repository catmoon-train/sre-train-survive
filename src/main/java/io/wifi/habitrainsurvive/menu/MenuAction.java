package io.wifi.habitrainsurvive.menu;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Configuration-only action model: deliberately independent of Minecraft bootstrap. */
public record MenuAction(Type type, String value, String permission, float volume, float pitch) {
    public enum Type { OPEN, BACK, PLAYER_COMMAND, CONSOLE_COMMAND, TELEPORT, RTP, MESSAGE, SOUND, CLOSE }

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Set<String> IDENTITIES = Set.of("player", "uuid");
    static final Set<String> TEXT_PLACEHOLDERS = Set.of("player", "uuid", "balance", "world", "online",
            "group", "homes", "rtp_cooldown", "worlds", "loaded_worlds");

    public MenuAction {
        if (type == null || value == null || permission == null) throw new IllegalArgumentException("动作字段不能为 null");
        MenuConfig.permission(permission, "action.permission");
        if (!Float.isFinite(volume) || volume < 0 || volume > 4
                || !Float.isFinite(pitch) || pitch < 0.5f || pitch > 2) {
            throw new IllegalArgumentException("音效 volume 必须为 0..4，pitch 必须为 0.5..2");
        }
        if (type != Type.SOUND && (volume != 1 || pitch != 1)) {
            throw new IllegalArgumentException("仅 SOUND 可以设置 volume/pitch");
        }
        switch (type) {
            case OPEN -> MenuConfig.menuId(value, "action.value");
            case TELEPORT, RTP, SOUND -> MenuConfig.resourceId(value, "action.value");
            case PLAYER_COMMAND, CONSOLE_COMMAND -> validateCommand(value);
            case MESSAGE -> {
                if (value.length() > 2048 || value.chars().anyMatch(c -> c < 32 || c == 127)) {
                    throw new IllegalArgumentException("消息过长或含控制字符");
                }
                validatePlaceholders(value, TEXT_PLACEHOLDERS);
            }
            case CLOSE, BACK -> {
                if (!value.isEmpty()) throw new IllegalArgumentException(type + " 不接受 value");
            }
        }
    }

    private static void validateCommand(String command) {
        if (command.isBlank() || command.length() > 2048 || !command.equals(command.trim())
                || command.startsWith("/") || command.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("命令必须是无斜杠、无控制字符的单行命令（最多 2048 字符）");
        }
        validatePlaceholders(command, IDENTITIES);
    }

    static void validatePlaceholders(String text, Set<String> allowed) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '}') throw new IllegalArgumentException("未配对的占位符: " + text);
            if (c != '{') continue;
            int end = text.indexOf('}', i + 1);
            if (end < 0 || !allowed.contains(text.substring(i + 1, end))) {
                throw new IllegalArgumentException("未知或不允许的占位符: " + text);
            }
            i = end;
        }
    }

    /** Only a server entity's real account name is accepted; never a nickname or UI input. */
    public static String safePlayerName(String name) {
        if (name == null || !PLAYER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("玩家账户名不能安全用于命令");
        }
        return name;
    }

    public String identityCommand(String accountName, UUID uuid) {
        if (type != Type.PLAYER_COMMAND && type != Type.CONSOLE_COMMAND) {
            throw new IllegalStateException("不是命令动作");
        }
        return value.replace("{player}", safePlayerName(accountName)).replace("{uuid}", uuid.toString());
    }

    /** Brigadier word arguments do not accept spaces, quotes, selectors or newlines. */
    public static String safeWord(String word) {
        if (word == null || word.isEmpty() || word.length() > 256
                || !word.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'A' && c <= 'Z'
                || c >= 'a' && c <= 'z' || c == '_' || c == '-' || c == '.' || c == '+')) {
            throw new IllegalArgumentException("名称不能安全用于单词命令参数");
        }
        return word;
    }
}
