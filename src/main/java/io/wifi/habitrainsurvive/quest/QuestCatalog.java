package io.wifi.habitrainsurvive.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validated, immutable view of the editable JSON. Rejects a whole file instead of silently
 * dropping broken quests, so an editor mistake cannot erase rewards or dependencies.
 */
public final class QuestCatalog {
    private final QuestDefinition.Settings settings;
    private final List<QuestDefinition.Quest> daily;
    private final List<QuestDefinition.Quest> weekly;
    private final List<QuestDefinition.Chapter> chapters;
    private final Map<String, QuestDefinition.Quest> byId;
    private final Map<String, String> chapterOf;

    private QuestCatalog(QuestDefinition.Settings settings, List<QuestDefinition.Quest> daily,
                         List<QuestDefinition.Quest> weekly, List<QuestDefinition.Chapter> chapters) {
        this.settings = settings;
        this.daily = List.copyOf(daily);
        this.weekly = List.copyOf(weekly);
        this.chapters = List.copyOf(chapters);
        Map<String, QuestDefinition.Quest> all = new LinkedHashMap<>();
        Map<String, String> owners = new HashMap<>();
        for (QuestDefinition.Quest quest : this.daily) all.put(quest.id, quest);
        for (QuestDefinition.Quest quest : this.weekly) all.put(quest.id, quest);
        for (QuestDefinition.Chapter chapter : this.chapters) {
            for (QuestDefinition.Quest quest : chapter.quests) {
                all.put(quest.id, quest);
                owners.put(quest.id, chapter.id);
            }
        }
        this.byId = Map.copyOf(all);
        this.chapterOf = Map.copyOf(owners);
    }

    public static QuestCatalog parse(com.google.gson.Gson gson, String json) {
        QuestDefinition.Bundle bundle;
        try {
            bundle = gson.fromJson(json, QuestDefinition.Bundle.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("任务 JSON 无法解析: " + exception.getMessage());
        }
        if (bundle == null) throw new IllegalArgumentException("任务 JSON 为空");
        QuestDefinition.Bundle fixed = normalize(bundle);
        List<String> errors = new ArrayList<>();
        validate(fixed, errors);
        if (!errors.isEmpty()) {
            String shown = String.join("\n", errors.subList(0, Math.min(errors.size(), 25)));
            throw new IllegalArgumentException("任务 JSON 校验失败（共 " + errors.size() + " 处）:\n" + shown);
        }
        return new QuestCatalog(fixed.settings, fixed.daily, fixed.weekly, fixed.chapters);
    }

    /** Used only when the on-disk file is unreadable; keeps the server bootable without inventing quests. */
    public static QuestCatalog empty() {
        QuestDefinition.Bundle bundle = new QuestDefinition.Bundle();
        bundle.settings.dailyCount = 0;
        bundle.settings.weeklyCount = 0;
        return new QuestCatalog(bundle.settings, bundle.daily, bundle.weekly, bundle.chapters);
    }

    private static QuestDefinition.Bundle normalize(QuestDefinition.Bundle bundle) {
        if (bundle.settings == null) bundle.settings = new QuestDefinition.Settings();
        if (bundle.settings.timeZone == null || bundle.settings.timeZone.isBlank()) {
            bundle.settings.timeZone = "Asia/Shanghai";
        }
        bundle.daily = list(bundle.daily);
        bundle.weekly = list(bundle.weekly);
        bundle.chapters = list(bundle.chapters);
        for (QuestDefinition.Quest quest : concat(bundle.daily, bundle.weekly, bundle.chapters)) {
            if (quest.icon == null || quest.icon.isBlank()) quest.icon = "minecraft:paper";
            if (quest.tasks == null) quest.tasks = new ArrayList<>();
            if (quest.rewards == null) quest.rewards = new ArrayList<>();
            if (quest.requires == null) quest.requires = new ArrayList<>();
            for (QuestDefinition.Reward reward : quest.rewards) {
                if (reward.type == null) reward.type = "";
                if (reward.item == null) reward.item = "";
            }
            for (QuestDefinition.Task task : quest.tasks) {
                if (task.type == null) task.type = "";
                if (task.target == null) task.target = "";
                if (task.display == null) task.display = "";
            }
        }
        for (QuestDefinition.Chapter chapter : bundle.chapters) {
            if (chapter.icon == null || chapter.icon.isBlank()) chapter.icon = "minecraft:grass_block";
            chapter.quests = list(chapter.quests);
            if (chapter.title == null) chapter.title = "";
        }
        return bundle;
    }

    private static List<QuestDefinition.Quest> concat(List<QuestDefinition.Quest> daily,
                                                      List<QuestDefinition.Quest> weekly,
                                                      List<QuestDefinition.Chapter> chapters) {
        List<QuestDefinition.Quest> all = new ArrayList<>(daily);
        all.addAll(weekly);
        for (QuestDefinition.Chapter chapter : chapters) all.addAll(chapter.quests);
        return all;
    }

    private static <T> List<T> list(List<T> value) { return value == null ? new ArrayList<>() : value; }

    private static void validate(QuestDefinition.Bundle bundle, List<String> errors) {
        Set<String> ids = new HashSet<>();
        Set<String> chapterIds = new HashSet<>();
        for (QuestDefinition.Chapter chapter : bundle.chapters) {
            if (chapter.id == null || chapter.id.isBlank()) errors.add("章节缺少 id: " + chapter.label());
            else if (!chapterIds.add(chapter.id)) errors.add("章节 id 重复: " + chapter.id);
        }
        for (QuestDefinition.Quest quest : bundle.daily) checkQuest(quest, "每日", ids, false, errors);
        for (QuestDefinition.Quest quest : bundle.weekly) checkQuest(quest, "每周", ids, false, errors);
        for (QuestDefinition.Chapter chapter : bundle.chapters) {
            for (QuestDefinition.Quest quest : chapter.quests) checkQuest(quest, "章节 " + chapter.label(), ids, true, errors);
        }
        if (bundle.settings.dailyCount < 0 || bundle.settings.dailyCount > 45) errors.add("每日刷新数量必须在 0..45");
        if (bundle.settings.weeklyCount < 0 || bundle.settings.weeklyCount > 45) errors.add("每周刷新数量必须在 0..45");
        if (!bundle.daily.isEmpty() && bundle.settings.dailyCount == 0) errors.add("每日模板非空时每日刷新数量不能为 0");
        if (!bundle.weekly.isEmpty() && bundle.settings.weeklyCount == 0) errors.add("每周模板非空时每周刷新数量不能为 0");
        if (bundle.settings.dailyCount > bundle.daily.size()) errors.add("每日刷新数量超过每日模板数量");
        if (bundle.settings.weeklyCount > bundle.weekly.size()) errors.add("每周刷新数量超过每周模板数量");
        try { java.time.ZoneId.of(bundle.settings.timeZone); }
        catch (RuntimeException exception) { errors.add("时区无效: " + bundle.settings.timeZone); }

        Map<String, QuestDefinition.Quest> byId = new HashMap<>();
        for (QuestDefinition.Quest quest : concat(bundle.daily, bundle.weekly, bundle.chapters)) byId.put(quest.id, quest);
        Map<String, Integer> state = new HashMap<>();
        for (QuestDefinition.Quest quest : byId.values()) {
            if (cycle(quest, byId, state, new ArrayList<>())) {
                errors.add("任务前置依赖形成循环: " + quest.id);
                break;
            }
        }
    }

    private static void checkQuest(QuestDefinition.Quest quest, String group, Set<String> ids,
                                   boolean chapter, List<String> errors) {
        String label = quest.id == null || quest.id.isBlank() ? quest.label() : quest.id;
        if (quest.id == null || !quest.id.matches("[A-Za-z0-9_.-]{1,64}")) {
            errors.add(group + " 任务 id 无效（1-64 位字母数字 _ . -）: " + label);
        } else if (!ids.add(quest.id)) {
            errors.add("任务 id 重复: " + quest.id);
        }
        if (quest.title == null || quest.title.isBlank()) errors.add("任务缺少标题: " + label);
        if (quest.tasks.isEmpty()) errors.add("任务没有目标: " + label);
        if (quest.rewards.isEmpty()) errors.add("任务没有奖励: " + label);
        for (QuestDefinition.Task task : quest.tasks) {
            QuestTrigger.Type type = QuestTrigger.types().get(task.type);
            if (type == null) {
                errors.add("未知触发器类型 " + task.type + "（任务 " + label + "）");
                continue;
            }
            if (task.amount <= 0) errors.add("目标数量必须为正: " + label);
            if (type.targetKind() != QuestTrigger.TargetKind.NONE
                    && (task.target == null || task.target.isBlank())) {
                errors.add("触发器 " + task.type + " 缺少目标: " + label);
            }
        }
        for (QuestDefinition.Reward reward : quest.rewards) {
            switch (reward.type == null ? "" : reward.type) {
                case "coin" -> { if (reward.amount < 0 || reward.amount > 1e12) errors.add("金币奖励超出范围: " + label); }
                case "xp" -> { if (reward.amount <= 0 || reward.amount > 1_000_000) errors.add("经验奖励超出范围: " + label); }
                case "item" -> {
                    if (reward.item == null || reward.item.isBlank()) errors.add("物品奖励缺少物品 id: " + label);
                    if (reward.count <= 0 || reward.count > 2304) errors.add("物品奖励数量必须在 1..2304: " + label);
                }
                default -> errors.add("未知奖励类型 " + reward.type + "（任务 " + label + "）");
            }
        }
        if (chapter) {
            if (quest.x < 0 || quest.y < 0 || quest.x > 64 || quest.y > 64) errors.add("章节任务坐标超出 0..64: " + label);
        } else {
            quest.requires = new ArrayList<>();
            quest.x = 0;
            quest.y = 0;
        }
    }

    private static boolean cycle(QuestDefinition.Quest quest, Map<String, QuestDefinition.Quest> byId,
                                 Map<String, Integer> state, List<String> path) {
        int mark = state.getOrDefault(quest.id, 0);
        if (mark == 1) return true;
        if (mark == 2) return false;
        state.put(quest.id, 1);
        for (String required : quest.requires) {
            QuestDefinition.Quest next = byId.get(required);
            if (next != null && cycle(next, byId, state, path)) return true;
        }
        state.put(quest.id, 2);
        return false;
    }

    public QuestDefinition.Settings settings() { return settings; }
    public List<QuestDefinition.Quest> daily() { return daily; }
    public List<QuestDefinition.Quest> weekly() { return weekly; }
    public List<QuestDefinition.Chapter> chapters() { return chapters; }
    public QuestDefinition.Quest quest(String id) { return id == null ? null : byId.get(id); }
    public String chapterOf(String questId) { return chapterOf.get(questId); }
    public List<QuestDefinition.Quest> chapterQuests(String chapterId) {
        for (QuestDefinition.Chapter chapter : chapters) if (chapter.id.equals(chapterId)) return chapter.quests;
        return List.of();
    }
    public QuestDefinition.Chapter chapter(String chapterId) {
        for (QuestDefinition.Chapter chapter : chapters) if (chapter.id.equals(chapterId)) return chapter;
        return null;
    }
    public Set<String> allIds() { return byId.keySet(); }

    /** Whether an unlocked chapter quest may count progress and be claimed. */
    public boolean unlocked(String questId, Set<String> completedChapterQuests) {
        QuestDefinition.Quest quest = byId.get(questId);
        if (quest == null) return false;
        if (!chapterOf.containsKey(questId)) return true;
        for (String required : quest.requires) if (!completedChapterQuests.contains(required)) return false;
        return true;
    }

    static String normalizeId(String id) { return id == null ? "" : id.toLowerCase(Locale.ROOT); }
}
