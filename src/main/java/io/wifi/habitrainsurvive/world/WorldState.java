package io.wifi.habitrainsurvive.world;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import java.util.*;

public final class WorldState {
    public Map<String, Runtime> worlds = new LinkedHashMap<>();
    public Map<UUID, Visit> lastVisits = new HashMap<>();
    public static final class Runtime {
        public long generation = 0, lastReset = System.currentTimeMillis(), dayTime = 0;
        public boolean pendingReset = false, imported = false, hubBuilt = false;
        public int hubBuildCursor = 0;
        public String resetBackup;
        public boolean resetHadDirectory;
        public long resetGeneration;
        public GlobalPosInfo spawn;
    }
    public record Visit(String dimension, long generation) {}
    public Runtime world(String id) { return worlds.computeIfAbsent(id, ignored -> new Runtime()); }
    public void validate() {
        if (worlds == null || lastVisits == null) throw new IllegalArgumentException("世界状态缺少 worlds/lastVisits");
        for (var entry : worlds.entrySet()) {
            WorldDefinition.validateId(entry.getKey());
            Runtime r = entry.getValue();
            if (r == null || r.generation < 0 || r.lastReset < 0 || r.hubBuildCursor < 0) throw new IllegalArgumentException("世界状态损坏: " + entry.getKey());
        }
    }
}
