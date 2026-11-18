package io.wifi.habitrainsurvive.menu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MenuInputPolicyTest {
    @Test void onlyLeftOrRightPickupAndQuickMoveCanActivateDisplayButtons() {
        for (String type : new String[]{"PICKUP", "QUICK_MOVE"}) {
            assertTrue(MenuInputPolicy.allows(type, 0, 0, 54));
            assertTrue(MenuInputPolicy.allows(type, 1, 53, 54));
            for (int button : new int[]{-1, 2, 3, 8, 40}) assertFalse(MenuInputPolicy.allows(type, button, 0, 54));
        }
    }

    @Test void hotbarOffhandDragCloneDoubleClickAndThrowCannotActivateAnything() {
        for (String type : new String[]{"SWAP", "QUICK_CRAFT", "CLONE", "PICKUP_ALL", "THROW", "UNKNOWN"}) {
            for (int button = 0; button <= 40; button++) assertFalse(MenuInputPolicy.allows(type, button, 0, 54), type);
        }
    }

    @Test void playerInventoryAndOutsideNeverMapToMenuButtonsForAnyRowCount() {
        for (int rows = 1; rows <= 6; rows++) {
            int display = rows * 9;
            for (String type : new String[]{"PICKUP", "QUICK_MOVE"}) {
                for (int button = 0; button <= 1; button++) {
                    assertFalse(MenuInputPolicy.allows(type, button, -999, display));
                    assertFalse(MenuInputPolicy.allows(type, button, -1, display));
                    for (int slot = display; slot < display + 36; slot++) assertFalse(MenuInputPolicy.allows(type, button, slot, display));
                }
            }
        }
    }
}
