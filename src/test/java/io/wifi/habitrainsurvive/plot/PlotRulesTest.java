package io.wifi.habitrainsurvive.plot;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlotRulesTest {
    @Test void onlyFirstHistoricalPurchaseIsFreeAndOthersCost10000() {
        var rules = new PlotRules();
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        Set<UUID> freeUsed = new HashSet<>();
        assertEquals(0, rules.price(owner, freeUsed));
        freeUsed.add(owner);
        assertEquals(10000, rules.price(owner, freeUsed));
        assertEquals(0, rules.price(other, freeUsed));
        rules.additionalPlotPrice = 12000;
        assertEquals(12000, rules.price(owner, freeUsed));
    }
    @Test void missingRulesKeepSafeDefaultsAcrossJsonReload() {
        var rules = new Gson().fromJson("{}", PlotRules.class);
        rules.validate();
        assertEquals(10000, rules.additionalPlotPrice);
        assertEquals(0, rules.maxPlotsPerPlayer);
        var restored = new Gson().fromJson(new Gson().toJson(rules), PlotRules.class);
        assertEquals(10000, restored.additionalPlotPrice);
    }
    @Test void pricesAndLimitsMustBeFiniteAndNonnegative() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 0, 1e13}) {
            var rules = new PlotRules(); rules.additionalPlotPrice = bad;
            assertThrows(IllegalArgumentException.class, rules::validate);
        }
        var rules = new PlotRules(); rules.maxPlotsPerPlayer = -1;
        assertThrows(IllegalArgumentException.class, rules::validate);
    }
}
