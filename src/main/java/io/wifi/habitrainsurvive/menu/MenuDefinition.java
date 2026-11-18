package io.wifi.habitrainsurvive.menu;

import java.util.List;

/** Immutable, validated configuration; all slot indices are zero-based. */
public record MenuDefinition(String id, String title, int rows, String permission, String back,
                             String dynamic, List<Entry> buttons) {
    public MenuDefinition {
        buttons = List.copyOf(buttons);
    }

    public record Entry(int slot, String icon, String name, List<String> lore, String permission,
                        List<MenuAction> actions, int amount) {
        public Entry(int slot, String icon, String name, List<String> lore, String permission,
                     List<MenuAction> actions) {
            this(slot, icon, name, lore, permission, actions, 1);
        }

        public Entry {
            lore = List.copyOf(lore);
            actions = List.copyOf(actions);
        }
    }
}
