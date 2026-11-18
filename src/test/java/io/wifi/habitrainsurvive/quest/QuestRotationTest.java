package io.wifi.habitrainsurvive.quest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QuestRotationTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static List<String> templates(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) ids.add("t" + i);
        return ids;
    }

    @Test void samePlayerAndPeriodAlwaysGetTheSameQuestIds() {
        List<String> template = templates(25);
        List<String> first = QuestManager.pickRotation(ALICE, "2026-06-01", "daily", template, 5);
        List<String> second = QuestManager.pickRotation(ALICE, "2026-06-01", "daily", template, 5);
        assertEquals(first, second);
        assertEquals(5, first.size());
        assertEquals(5, first.stream().distinct().count());
        assertTrue(template.containsAll(first));
    }

    @Test void nextPeriodAndOtherPlayersRotate() {
        List<String> template = templates(25);
        List<String> today = QuestManager.pickRotation(ALICE, "2026-06-01", "daily", template, 5);
        List<String> tomorrow = QuestManager.pickRotation(ALICE, "2026-06-02", "daily", template, 5);
        assertNotEquals(today, tomorrow);
        List<String> other = QuestManager.pickRotation(BOB, "2026-06-01", "daily", template, 5);
        assertNotEquals(today, other);
    }

    @Test void countIsClampedAndEmptyTemplatesAreSafe() {
        assertEquals(25, QuestManager.pickRotation(ALICE, "p", "daily", templates(25), 999).size());
        assertEquals(0, QuestManager.pickRotation(ALICE, "p", "daily", templates(0), 5).size());
        assertEquals(0, QuestManager.pickRotation(ALICE, "p", "daily", templates(25), 0).size());
    }

    @Test void weeklyAndDailyUseDifferentSelections() {
        List<String> template = templates(25);
        assertNotEquals(QuestManager.pickRotation(ALICE, "2026-W23", "daily", template, 5),
                QuestManager.pickRotation(ALICE, "2026-W23", "weekly", template, 5));
    }
}
