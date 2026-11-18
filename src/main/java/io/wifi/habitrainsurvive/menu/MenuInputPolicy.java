package io.wifi.habitrainsurvive.menu;

/** Pure click gate, also used by tests without initializing Minecraft registries. */
final class MenuInputPolicy {
    private MenuInputPolicy() { }

    static boolean allows(String clickType, int mouseButton, int slot, int displaySlots) {
        return slot >= 0 && slot < displaySlots && (mouseButton == 0 || mouseButton == 1)
                && ("PICKUP".equals(clickType) || "QUICK_MOVE".equals(clickType));
    }
}
