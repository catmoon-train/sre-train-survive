package io.wifi.habitrainsurvive.economy;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ExperiencePaymentTest {
    @Test void matchesVanillaExperienceCurve() {
        assertEquals(7, ExperienceMath.nextLevelCost(0));
        assertEquals(9, ExperienceMath.nextLevelCost(1));
        assertEquals(37, ExperienceMath.nextLevelCost(15));
        assertEquals(42, ExperienceMath.nextLevelCost(16));
        assertEquals(112, ExperienceMath.nextLevelCost(30));
        assertEquals(121, ExperienceMath.nextLevelCost(31));
        assertEquals(0, ExperienceMath.totalForLevel(0));
        assertEquals(7, ExperienceMath.totalForLevel(1));
        assertEquals(1395, ExperienceMath.totalForLevel(30));
    }
    @Test void totalAndStateRoundTripAtProgressBoundaries() {
        Random random = new Random(20261117);
        for (int i = 0; i < 300; i++) {
            long total = random.nextInt(1_000_000);
            ExperienceMath.State state = ExperienceMath.fromTotal(total);
            assertEquals(total, ExperienceMath.totalFor(state.level(), state.progress()));
        }
        for (int level = 0; level <= 100; level++) {
            long total = ExperienceMath.totalForLevel(level);
            assertEquals(level, ExperienceMath.fromTotal(total).level());
            assertEquals(0.0, ExperienceMath.fromTotal(total).progress(), 1e-12);
        }
    }
    @Test void levelPaymentPreservesProgressAndOnlyRemovesWholeLevels() {
        ExperienceMath.State old = new ExperienceMath.State(30, 0.5, ExperienceMath.totalFor(30, 0.5));
        ExperienceMath.State next = ExperienceMath.payLevels(old, 5);
        assertEquals(25, next.level());
        assertEquals(0.5, next.progress(), 1e-12);
        assertEquals(ExperienceMath.totalFor(25, 0.5), next.total());
        assertEquals(0, ExperienceMath.payLevels(old, 30).level());
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.payLevels(old, 31));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.payLevels(old, 0));
    }

    @Test void pointPaymentIsExactAndNeverNegative() {
        ExperienceMath.State old = ExperienceMath.fromTotal(1000);
        ExperienceMath.State next = ExperienceMath.payPoints(old, 100);
        assertEquals(900, next.total());
        assertEquals(0, ExperienceMath.payPoints(old, 1000).total());
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.payPoints(old, 1001));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.payPoints(old, -1));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.payPoints(old, 0));
    }

    @Test void experienceCostValidatesModesAmountsAndScalesSafely() {
        ExperienceCost none = new ExperienceCost();
        assertFalse(none.enabled());
        assertEquals("未启用", none.label());
        ExperienceCost levels = new ExperienceCost(ExperienceCost.LEVELS, 3);
        assertEquals("3 级经验", levels.label());
        assertEquals("27 级经验", levels.label(9));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCost("BAD", 1));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCost(ExperienceCost.LEVELS, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCost(ExperienceCost.LEVELS, -1));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCost(ExperienceCost.POINTS, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCost(ExperienceCost.POINTS, ExperienceCost.MAX_POINTS + 1));
    }

    @Test void invalidExperienceCostIsRejectedAndLegacyDataMigratesDisabled() {
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCost("BAD", 1));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCost(ExperienceCost.LEVELS, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCost(ExperienceCost.POINTS, ExperienceCost.MAX_POINTS + 1));
        ExperienceCost damaged = new ExperienceCost();
        damaged.mode = "BAD"; damaged.amount = 20; damaged.migrate();
        assertFalse(damaged.enabled()); assertEquals("NONE", damaged.mode);
        ExperienceCost restored = new Gson().fromJson("{\"mode\":\"POINTS\",\"amount\":120}", ExperienceCost.class);
        assertTrue(restored.enabled()); assertEquals("120 点经验", restored.label());
        assertThrows(IllegalArgumentException.class, () -> restored.scaledAmount(0));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCost(ExperienceCost.POINTS, ExperienceCost.MAX_POINTS).scaledAmount(2));
    }
}
