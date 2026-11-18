package io.wifi.habitrainsurvive.plot;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class PlotGridTest {
    @Test void positiveAndNegativeBoundariesReserveExactly64Blocks() {
        for (int gx = -3; gx <= 3; gx++) for (int gz = -3; gz <= 3; gz++) {
            var cell = new PlotGrid.Cell(gx, gz);
            assertEquals(64, cell.maxX() - cell.minX() + 1);
            assertEquals(64, cell.maxZ() - cell.minZ() + 1);
            assertEquals(cell, PlotGrid.at(cell.minX(), cell.minZ()));
            assertEquals(cell, PlotGrid.at(cell.maxX(), cell.maxZ()));
            assertNull(PlotGrid.at(cell.minX() - 1, cell.minZ()));
            assertNull(PlotGrid.at(cell.maxX() + 1, cell.maxZ()));
            assertNull(PlotGrid.at(cell.minX(), cell.minZ() - 1));
            assertNull(PlotGrid.at(cell.maxX(), cell.maxZ() + 1));
        }
    }
    @Test void originIsPublicRoadAndEachPeriodHasSevenRoadColumns() {
        assertNull(PlotGrid.at(0, 0));
        int roads = 0;
        for (int x = -71; x < 0; x++) if (PlotGrid.at(x, 10) == null) roads++;
        assertEquals(7, roads);
    }
    @Test void spiralStartsNearSpawnAndNeverDuplicatesCells() {
        assertEquals(new PlotGrid.Cell(0, 0), PlotGrid.candidate(0));
        assertEquals(new PlotGrid.Cell(1, 0), PlotGrid.candidate(1));
        assertEquals(new PlotGrid.Cell(1, -1), PlotGrid.candidate(8));
        var ids = new HashSet<String>();
        for (int i = 0; i < 10000; i++) assertTrue(ids.add(PlotGrid.candidate(i).id()), "duplicate " + i);
        assertEquals(10000, ids.size());
        assertThrows(IllegalArgumentException.class, () -> PlotGrid.candidate(-1));
    }
    @Test void borderRequiresWholePlotAndSafeEntranceToFit() {
        assertTrue(new PlotGrid.Cell(0, 0).withinBorder(20000));
        assertTrue(new PlotGrid.Cell(-1, -1).withinBorder(20000));
        assertFalse(new PlotGrid.Cell(1, 1).withinBorder(128));
        assertFalse(new PlotGrid.Cell(1000, 1000).withinBorder(20000));
    }
}
