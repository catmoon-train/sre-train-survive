package io.wifi.habitrainsurvive.quest;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class QuestCatalogTest {
    private static final Gson GSON = new Gson();

    static String defaults() throws Exception {
        try (InputStream stream = QuestCatalogTest.class.getResourceAsStream("/defaults/quests.json")) {
            assertNotNull(stream, "内置任务模板缺失");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void shippedTemplatesParseAndMatchTheDocumentedCounts() throws Exception {
        QuestCatalog catalog = QuestCatalog.parse(GSON, defaults());
        assertEquals(25, catalog.daily().size());
        assertEquals(25, catalog.weekly().size());
        assertEquals(2, catalog.chapters().size());
        assertEquals(5, catalog.settings().dailyCount);
        assertEquals(5, catalog.settings().weeklyCount);
        assertEquals("Asia/Shanghai", catalog.settings().timeZone);
        for (String id : catalog.allIds()) assertNotNull(catalog.quest(id));
        assertTrue(catalog.unlocked("ch_wood", java.util.Set.of()));
        assertFalse(catalog.unlocked("ch_stone", java.util.Set.of()));
        assertTrue(catalog.unlocked("ch_stone", java.util.Set.of("ch_wood", "ch_pickaxe")));
    }

    @Test void dailyAndWeeklyTemplatesNeverCarryChapterDependencies() throws Exception {
        QuestCatalog catalog = QuestCatalog.parse(GSON, defaults());
        for (QuestDefinition.Quest quest : catalog.daily()) assertTrue(quest.requires.isEmpty());
        for (QuestDefinition.Quest quest : catalog.weekly()) assertTrue(quest.requires.isEmpty());
        assertNull(catalog.chapterOf("daily_mine_any"));
        assertEquals("chapter_survival", catalog.chapterOf("ch_wood"));
    }

    @Test void duplicateIdsAreRejectedInsteadOfSilentlyMerged() {
        String json = """
                {"settings":{"dailyCount":1,"weeklyCount":0},
                 "daily":[{"id":"same","title":"a","tasks":[{"type":"mine_block","target":"*","amount":1}],"rewards":[{"type":"xp","amount":1}]}],
                 "weekly":[{"id":"same","title":"b","tasks":[{"type":"mine_block","target":"*","amount":1}],"rewards":[{"type":"xp","amount":1}]}],
                 "chapters":[]}
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> QuestCatalog.parse(GSON, json));
        assertTrue(error.getMessage().contains("重复"), error.getMessage());
    }

    @Test void unknownTriggerEmptyRewardAndBadCountsAreRejected() {
        String unknownTrigger = """
                {"settings":{"dailyCount":1,"weeklyCount":0},"daily":[
                  {"id":"a","title":"a","tasks":[{"type":"teleport","target":"*","amount":1}],"rewards":[{"type":"xp","amount":1}]}],
                 "weekly":[],"chapters":[]}
                """;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> QuestCatalog.parse(GSON, unknownTrigger))
                .getMessage().contains("未知触发器"));

        String noReward = """
                {"settings":{"dailyCount":1,"weeklyCount":0},"daily":[
                  {"id":"a","title":"a","tasks":[{"type":"mine_block","target":"*","amount":1}],"rewards":[]}],
                 "weekly":[],"chapters":[]}
                """;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> QuestCatalog.parse(GSON, noReward))
                .getMessage().contains("没有奖励"));

        String tooMany = """
                {"settings":{"dailyCount":3,"weeklyCount":0},"daily":[
                  {"id":"a","title":"a","tasks":[{"type":"mine_block","target":"*","amount":1}],"rewards":[{"type":"xp","amount":1}]}],
                 "weekly":[],"chapters":[]}
                """;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> QuestCatalog.parse(GSON, tooMany))
                .getMessage().contains("超过"));
    }

    @Test void dependencyCyclesAreRejected() {
        String cyclic = """
                {"settings":{"dailyCount":0,"weeklyCount":0},"daily":[],"weekly":[],"chapters":[
                  {"id":"c","title":"c","quests":[
                    {"id":"a","title":"a","requires":["b"],"tasks":[{"type":"online_time","target":"","amount":1}],"rewards":[{"type":"xp","amount":1}]},
                    {"id":"b","title":"b","requires":["a"],"tasks":[{"type":"online_time","target":"","amount":1}],"rewards":[{"type":"xp","amount":1}]}]}]}
                """;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> QuestCatalog.parse(GSON, cyclic))
                .getMessage().contains("循环"));
    }

    @Test void malformedJsonNeverYieldsAnEmptyCatalog() {
        assertThrows(IllegalArgumentException.class, () -> QuestCatalog.parse(GSON, "{not json"));
        assertThrows(IllegalArgumentException.class, () -> QuestCatalog.parse(GSON, "null"));
    }
}
