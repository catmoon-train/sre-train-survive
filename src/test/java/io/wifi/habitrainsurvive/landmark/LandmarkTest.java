package io.wifi.habitrainsurvive.landmark;

import com.google.gson.Gson;
import io.wifi.habitrainsurvive.cmi.WarpPoint;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandmarkTest {
    @Test void legacyWarpsRemainEnabledAndKeepTheirOriginalPrices() {
        var warp = new Gson().fromJson("{\"name\":\"old\",\"cost\":25}", WarpPoint.class);
        assertTrue(warp.enabled);
        assertEquals(25, warp.cost);
        assertEquals("minecraft:ender_pearl", warp.icon);
        assertEquals("", warp.description);
    }
    @Test void newLandmarkIdsAreCanonicalAndCannotContainCommandText() {
        assertEquals("station_1", LandmarkManager.id(" Station_1 "));
        for (String input : new String[]{"", "two words", "a\nb", "../path", "a:b", "a/b", "x".repeat(33)})
            assertThrows(IllegalArgumentException.class, () -> LandmarkManager.id(input));
    }
}
