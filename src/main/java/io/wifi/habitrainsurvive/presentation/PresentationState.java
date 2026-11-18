package io.wifi.habitrainsurvive.presentation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Kept separate from CMI's profile; an absent entry uses current server defaults. */
public final class PresentationState {
    public int version = 1;
    public Map<UUID, Preferences> players = new HashMap<>();

    public static final class Preferences {
        public boolean sidebar = true;
        public boolean announcements = true;
        public boolean sounds = true;

        public Preferences() { }

        public Preferences(boolean sidebar, boolean announcements, boolean sounds) {
            this.sidebar = sidebar;
            this.announcements = announcements;
            this.sounds = sounds;
        }
    }

    public void validate() {
        if (version != 1 || players == null || players.containsKey(null) || players.containsValue(null)) {
            throw new IllegalArgumentException("presentation state: invalid version or player preferences");
        }
    }
}
