package io.wifi.habitrainsurvive.menu;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MenuActionTest {
    private static MenuAction action(MenuAction.Type type, String value) {
        return new MenuAction(type, value, "", 1, 1);
    }

    @Test void consoleAndPlayerCommandsOnlySubstituteValidatedEntityIdentities() {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        for (MenuAction.Type type : new MenuAction.Type[]{MenuAction.Type.CONSOLE_COMMAND, MenuAction.Type.PLAYER_COMMAND}) {
            MenuAction action = action(type, "test {player} {uuid} {player}");
            assertEquals("test Player_123 " + uuid + " Player_123", action.identityCommand("Player_123", uuid));
            assertThrows(IllegalArgumentException.class, () -> action.identityCommand("@a", uuid));
            assertThrows(IllegalArgumentException.class, () -> action.identityCommand("Alice\nop Bob", uuid));
            assertThrows(IllegalArgumentException.class, () -> action.identityCommand("Display Name", uuid));
            for (String command : new String[]{"say {input}", "say {balance}", "say {world}", "say {args}",
                    "say {player.name}", "say {player", "say player}", "say {player}\nstop", "/op {player}", " ", " say test"}) {
                assertThrows(IllegalArgumentException.class, () -> action(type, command), command);
            }
        }
    }

    @Test void permissionNodesAreRetainedAndValidatedWithoutGrantingAnything() {
        MenuAction action = new MenuAction(MenuAction.Type.PLAYER_COMMAND, "habi status", "habi.admin", 1, 1);
        assertEquals("habi.admin", action.permission());
        assertEquals(MenuAction.Type.PLAYER_COMMAND, action.type());
        for (String node : new String[]{"*", "habi.*", "UPPER.CASE", "habi admin", ".habi", "habi..admin"}) {
            assertThrows(IllegalArgumentException.class, () -> new MenuAction(MenuAction.Type.CLOSE, "", node, 1, 1));
        }
    }

    @Test void validatesActionSpecificArgumentsAndSoundBounds() {
        assertDoesNotThrow(() -> action(MenuAction.Type.TELEPORT, "habi_train_survive:hub"));
        assertDoesNotThrow(() -> action(MenuAction.Type.RTP, "minecraft:overworld"));
        assertDoesNotThrow(() -> action(MenuAction.Type.MESSAGE, "{player}: {balance} / {rtp_cooldown}"));
        assertThrows(IllegalArgumentException.class, () -> action(MenuAction.Type.TELEPORT, "overworld"));
        assertThrows(IllegalArgumentException.class, () -> action(MenuAction.Type.CLOSE, "unexpected"));
        assertThrows(IllegalArgumentException.class, () -> action(MenuAction.Type.BACK, "main"));
        assertThrows(IllegalArgumentException.class, () -> action(MenuAction.Type.MESSAGE, "{input}"));
        assertThrows(IllegalArgumentException.class, () -> action(MenuAction.Type.MESSAGE, "hello\nworld"));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction(MenuAction.Type.SOUND, "minecraft:ui.button.click", "", Float.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction(MenuAction.Type.SOUND, "minecraft:ui.button.click", "", 5, 1));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction(MenuAction.Type.SOUND, "minecraft:ui.button.click", "", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new MenuAction(MenuAction.Type.CLOSE, "", "", 2, 1));
        assertThrows(IllegalStateException.class, () -> action(MenuAction.Type.OPEN, "main").identityCommand("Alice", UUID.randomUUID()));
    }

    @Test void dynamicArgumentsRejectSelectorsQuotesWhitespaceAndCommandInjection() {
        assertEquals("home_1.test-2+3", MenuAction.safeWord("home_1.test-2+3"));
        assertEquals("Player_123", MenuAction.safePlayerName("Player_123"));
        for (String word : new String[]{"", "@a", "name arg", "name\nstop", "name\targ", "\"name\"", "home/other"}) {
            assertThrows(IllegalArgumentException.class, () -> MenuAction.safeWord(word));
        }
        assertThrows(IllegalArgumentException.class, () -> MenuAction.safePlayerName("a".repeat(17)));
        assertThrows(IllegalArgumentException.class, () -> MenuAction.safePlayerName("§aNickname"));
    }
}
