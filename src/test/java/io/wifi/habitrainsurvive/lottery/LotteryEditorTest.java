package io.wifi.habitrainsurvive.lottery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LotteryEditorTest {
    @TempDir Path directory;

    @Test void normalizesSafeUniquePoolIds() {
        assertEquals("event_2026-1", LotteryEditValues.poolId(" EVENT_2026-1 "));
        for (String invalid : new String[]{"", "a b", "../pool", "mod:pool", "奖池", "a".repeat(33)}) {
            assertThrows(IllegalArgumentException.class, () -> LotteryEditValues.poolId(invalid));
        }
    }

    @Test void namesSupportColorAndRejectBlankOrOversizedValues() {
        assertEquals("§b夏日奖池", LotteryEditValues.name(" &b夏日奖池 "));
        assertThrows(IllegalArgumentException.class, () -> LotteryEditValues.name("  "));
        assertThrows(IllegalArgumentException.class, () -> LotteryEditValues.name("a".repeat(65)));
    }

    @Test void integersRespectStackWeightAndPityLimits() {
        assertEquals(1, LotteryEditValues.integer("1", 64));
        assertEquals(64, LotteryEditValues.integer(" 64 ", 64));
        for (String invalid : new String[]{"0", "-1", "65", "1.5", "NaN", "99999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> LotteryEditValues.integer(invalid, 64));
        }
        assertEquals(10000, LotteryEditValues.integer("10000", 10000));
        assertEquals(1000000, LotteryEditValues.integer("1000000", 1000000));
    }

    @Test void rejectsNonFiniteNegativeAndExcessiveCurrency() {
        assertEquals(0, LotteryEditValues.coins("0"));
        assertEquals(12.5, LotteryEditValues.coins("12.50"));
        assertEquals(1_000_000_000, LotteryEditValues.coins("1000000000"));
        for (String invalid : new String[]{"NaN", "Infinity", "-Infinity", "-1", "1000000001", "", "coins"}) {
            assertThrows(IllegalArgumentException.class, () -> LotteryEditValues.coins(invalid));
        }
    }

    @Test void savesAddedPoolsPrizesAndEditsAndDoesNotRestoreDeletedDefaults() throws Exception {
        Path path = directory.resolve("lottery.json");
        Files.writeString(path, "{\"pools\":{},\"playerPity\":{}}", StandardCharsets.UTF_8);
        LotteryManager manager = LotteryManager.get();
        // Existing stores do not need item registries merely to load or edit pool metadata.
        manager.load(path, null);
        assertTrue(manager.getPools().isEmpty());
        CratePool pool = new CratePool("rare", "测试奖池", 12.5, 30);
        CratePrize prize = new CratePrize("full-item-components", "自定义奖品", 16, 500, 8, true);
        pool.prizes.add(prize);
        assertNull(manager.getPools().putIfAbsent(pool.id, pool));
        assertSame(pool, manager.getPools().putIfAbsent(pool.id, new CratePool()));
        pool.experienceDrawCost = new io.wifi.habitrainsurvive.economy.ExperienceCost("LEVELS", 3);
        manager.saveChanges();
        manager.load(path, null);
        CratePool loaded = manager.getPool("RARE");
        assertEquals("测试奖池", loaded.displayName);
        assertEquals(12.5, loaded.singleDrawCost);
        assertEquals(30, loaded.pityThreshold);
        assertEquals("full-item-components", loaded.prizes.getFirst().serializedItem);
        assertEquals(16, loaded.prizes.getFirst().count);
        assertTrue(loaded.experienceDrawCost.levels());
        assertEquals(3, loaded.experienceDrawCost.amount);
        assertEquals(500, loaded.prizes.getFirst().bonusCoins);
        assertTrue(loaded.prizes.getFirst().rareBroadcast);
        loaded.prizes.getFirst().weight = 20;
        manager.saveChanges();
        manager.load(path, null);
        assertEquals(20, manager.getPool("rare").prizes.getFirst().weight);
        manager.getPlayerPity(UUID.randomUUID(), "rare");
        manager.deletePool(manager.getPool("rare"));
        manager.load(path, null);
        assertTrue(manager.getPools().isEmpty(), "删除默认奖池后重启不能重新生成");
    }

    @Test void deletingAStalePoolDoesNotDeleteItsReplacement() throws Exception {
        Path path = directory.resolve("lottery.json");
        Files.writeString(path, "{\"pools\":{}}", StandardCharsets.UTF_8);
        LotteryManager manager = LotteryManager.get();
        manager.load(path, null);
        CratePool stale = new CratePool("event", "旧奖池", 1, 10);
        CratePool current = new CratePool("event", "新奖池", 2, 20);
        manager.getPools().put(current.id, current);
        manager.deletePool(stale);
        assertSame(current, manager.getPool("event"));
    }
}
