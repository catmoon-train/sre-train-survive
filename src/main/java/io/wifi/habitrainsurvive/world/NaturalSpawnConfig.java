package io.wifi.habitrainsurvive.world;

/** One opt-out switch for the overworld's natural-spawn reduction. */
public final class NaturalSpawnConfig {
    public boolean enabled = true;

    public boolean skip(boolean overworld, boolean monster, boolean animal, boolean discardAnimalBatch) {
        return enabled && overworld && (monster || animal && discardAnimalBatch);
    }
}
