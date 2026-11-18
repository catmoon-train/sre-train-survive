package io.wifi.habitrainsurvive.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.*;

/** Vanilla DerivedLevelData shares mutable rules with the overworld. Managed worlds must not. */
public final class ManagedLevelData extends DerivedLevelData {
    private final String id;
    private final GameRules rules;
    private boolean raining, thundering;
    private int clearWeather = 6000, rainTime, thunderTime;
    public ManagedLevelData(WorldData world, ServerLevelData parent, String id) {
        super(world, parent); this.id = id; this.rules = parent.getGameRules().copy();
        WorldManager.get().registerData(id, this); refreshRules();
    }
    private WorldDefinition definition() { return WorldManager.get().definition(id); }
    public void refreshRules() {
        WorldDefinition d = definition(); if (d == null) return;
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(d.mobs, null);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(d.mobGrief, null);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(d.fireSpread, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(d.weather, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(d.fixedTime < 0, null);
    }
    @Override public GameRules getGameRules() { return rules; }
    @Override public Difficulty getDifficulty() { return Difficulty.byName(definition().difficulty); }
    @Override public BlockPos getSpawnPos() {
        var p = WorldManager.get().spawn(id); return p == null ? super.getSpawnPos() : p.toBlockPos();
    }
    @Override public float getSpawnAngle() { var p = WorldManager.get().spawn(id); return p == null ? 0 : p.yaw; }
    @Override public long getDayTime() { return definition().fixedTime >= 0 ? definition().fixedTime : WorldManager.get().runtime(id).dayTime; }
    @Override public void setDayTime(long value) { WorldManager.get().runtime(id).dayTime = value; }
    @Override public int getClearWeatherTime() { return clearWeather; }
    @Override public void setClearWeatherTime(int value) { clearWeather = value; }
    @Override public boolean isRaining() { return raining; }
    @Override public void setRaining(boolean value) { raining = value; }
    @Override public int getRainTime() { return rainTime; }
    @Override public void setRainTime(int value) { rainTime = value; }
    @Override public boolean isThundering() { return thundering; }
    @Override public void setThundering(boolean value) { thundering = value; }
    @Override public int getThunderTime() { return thunderTime; }
    @Override public void setThunderTime(int value) { thunderTime = value; }
}
