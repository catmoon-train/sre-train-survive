package io.wifi.habitrainsurvive.menu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiInputTest {
    @Test void numbersRejectNonfiniteValuesAndOutOfBounds() {
        assertEquals(12.5, GuiInput.number("12.5", 0, 100));
        for (String input : new String[]{"NaN", "Infinity", "1e9999", "-1", "101", "hello", ""})
            assertThrows(IllegalArgumentException.class, () -> GuiInput.number(input, 0, 100));
    }
    @Test void integersMustBeExactWithoutTruncationOrOverflow() {
        assertEquals(32, GuiInput.integer("32", 0, 100));
        for (String input : new String[]{"1.5", "-1", "101", "99999999999999999999999", "NaN"})
            assertThrows(IllegalArgumentException.class, () -> GuiInput.integer(input, 0, 100));
    }
    @Test void displayTextAllowsChineseButNotControlCharacters() {
        assertEquals("中文商店", GuiInput.text(" 中文商店 ", 20));
        for (String input : new String[]{"", "  ", "two\nlines", "\u0000bad"})
            assertThrows(IllegalArgumentException.class, () -> GuiInput.text(input, 20));
    }
}
