package io.wifi.habitrainsurvive.quest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension point for quest triggers. A plugin can register another type (for example a
 * dungeon-clear trigger) and it appears automatically in the generated HTML editor metadata.
 * Matching for built-in types stays in QuestManager because it needs registry access.
 */
public final class QuestTrigger {
    public enum TargetKind { NONE, ITEM, BLOCK, ENTITY }

    public record Type(String id, String label, TargetKind targetKind, String targetHint) { }

    private static final Map<String, Type> TYPES = new LinkedHashMap<>();

    static {
        register(new Type("kill_entity", "击杀实体", TargetKind.ENTITY, "实体 id、#标签 或 *"));
        register(new Type("mine_block", "挖掘方块", TargetKind.BLOCK, "方块 id、#标签 或 *"));
        register(new Type("place_block", "放置方块", TargetKind.BLOCK, "方块 id、#标签 或 *"));
        register(new Type("obtain_item", "获得物品", TargetKind.ITEM, "物品 id、#标签 或 *"));
        register(new Type("craft_item", "合成物品", TargetKind.ITEM, "物品 id、#标签 或 *"));
        register(new Type("use_item", "使用物品", TargetKind.ITEM, "物品 id、#标签 或 *"));
        register(new Type("submit_item", "提交物品", TargetKind.ITEM, "物品 id、#标签 或 *"));
        register(new Type("online_time", "在线时长", TargetKind.NONE, "无需目标，数量单位为秒"));
    }

    private QuestTrigger() { }

    public static synchronized void register(Type type) {
        if (type == null || type.id() == null || !type.id().matches("[a-z0-9_]{2,40}")) {
            throw new IllegalArgumentException("触发器 id 必须为 2-40 位小写字母数字或下划线");
        }
        TYPES.put(type.id(), type);
    }

    public static Map<String, Type> types() { return Map.copyOf(TYPES); }

    public static List<Map<String, String>> metadata() {
        List<Map<String, String>> list = new ArrayList<>();
        for (Type type : TYPES.values()) {
            list.add(Map.of("type", type.id(), "label", type.label(),
                    "targetKind", type.targetKind().name(), "targetHint", type.targetHint()));
        }
        return list;
    }

    public static String label(String typeId) {
        Type type = TYPES.get(typeId);
        return type == null ? typeId : type.label();
    }
}
