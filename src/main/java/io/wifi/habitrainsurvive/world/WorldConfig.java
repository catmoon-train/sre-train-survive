package io.wifi.habitrainsurvive.world;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import java.util.*;

public final class WorldConfig {
    public List<WorldDefinition> worlds = new ArrayList<>();
    public static WorldConfig defaults() {
        WorldConfig c = new WorldConfig();
        c.worlds.add(new WorldDefinition("minecraft:overworld", "生存世界", "normal", "survival"));
        c.worlds.add(new WorldDefinition("minecraft:the_nether", "生存下界", "nether", "survival"));
        c.worlds.add(new WorldDefinition("minecraft:the_end", "生存末地", "end", "survival"));
        WorldDefinition hub = new WorldDefinition("habi_train_survive:hub", "主城大厅", "void", "hub");
        hub.build = hub.pvp = hub.damage = hub.hunger = hub.mobs = hub.mobGrief = hub.explosions = false;
        hub.fireSpread = hub.fluids = hub.pistons = hub.allowClaims = hub.allowShops = hub.weather = hub.portals = false;
        hub.interact = false; hub.fixedTime = 6000; hub.borderSize = 192;
        hub.spawn = new GlobalPosInfo(hub.id, 0.5, 81, 0.5, 180, 0);
        c.worlds.add(hub);
        c.ensurePlotWorld();
        return c;
    }
    /** Additive migration only: existing worlds and explicitly configured plot rules are retained. */
    public boolean ensurePlotWorld() {
        if (worlds == null || worlds.stream().anyMatch(w -> w != null && "habi_train_survive:plots".equals(w.id))) return false;
        WorldDefinition plots = new WorldDefinition("habi_train_survive:plots", "地皮世界", "flat", "survival");
        plots.pvp = plots.mobs = plots.mobGrief = plots.explosions = plots.fireSpread = false;
        plots.fluids = plots.pistons = plots.allowClaims = plots.weather = plots.portals = false;
        plots.hunger = false; plots.fixedTime = 6000;
        plots.spawn = new GlobalPosInfo(plots.id, 0.5, -60, 0.5, 0, 0);
        worlds.add(plots);
        return true;
    }
    /** Removed dimension IDs remain blocked even if an old save or another pack still contains them. */
    public static boolean excludedId(String id) {
        return id != null && (id.equals("habi_train_survive:resource") || id.startsWith("habi_train_survive:resource_"));
    }
    public static boolean excluded(WorldDefinition world) { return world != null && (world.resource() || excludedId(world.id)); }
    public boolean removeExcludedWorlds() {
        if (worlds == null) return false;
        Set<String> excludedIds = new HashSet<>();
        boolean changed = false;
        for (WorldDefinition world : worlds) if (world != null && excluded(world)) {
            excludedIds.add(world.id);
            changed = true;
        }
        worlds.removeIf(world -> world != null && excluded(world));
        for (WorldDefinition world : worlds) if (world != null && !excluded(world)) {
            if (excludedId(world.overworld) || excludedIds.contains(world.overworld)) { world.overworld = "minecraft:overworld"; changed = true; }
            if (excludedId(world.nether) || excludedIds.contains(world.nether)) { world.nether = "minecraft:the_nether"; changed = true; }
            if (excludedId(world.end) || excludedIds.contains(world.end)) { world.end = "minecraft:the_end"; changed = true; }
        }
        return changed;
    }
    public void validate() {
        if (worlds == null || worlds.isEmpty() || worlds.size() > 100) throw new IllegalArgumentException("worlds 需包含1至100项");
        Set<String> ids = new HashSet<>();
        for (WorldDefinition w : worlds) {
            if (w == null) throw new IllegalArgumentException("worlds 包含 null");
            w.validate();
            if (excluded(w)) throw new IllegalArgumentException("资源维度已移除，不能登记: " + w.id);
            if (!ids.add(w.id)) throw new IllegalArgumentException("重复世界ID: " + w.id);
            if (!w.managed() && !Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end").contains(w.id))
                throw new IllegalArgumentException("自定义世界必须使用 habi_train_survive 命名空间: " + w.id);
            if (w.id.startsWith("minecraft:") && (!w.enabled || w.importSource != null)) throw new IllegalArgumentException("原版世界不能停用或导入覆盖");
            if (!w.managed() && (!w.mobs || !w.mobGrief || !w.weather || w.fixedTime != -1
                    || !"normal".equals(w.difficulty) || w.borderSize != 20000))
                throw new IllegalArgumentException(w.id + ": 原版三世界保留原版共享规则；mobs/mobGrief/weather/fixedTime/difficulty/borderSize 独立设置仅用于本核心维度，请使用原版命令管理原版规则");
        }
        for (String id : ids) if (id.startsWith("habi_train_survive:"))
            for (String other : ids) if (!id.equals(other) && other.startsWith(id + "/"))
                throw new IllegalArgumentException("世界目录不能嵌套: " + id + " / " + other);
        if (!ids.containsAll(Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "habi_train_survive:hub")))
            throw new IllegalArgumentException("不能移除原版三世界和默认主城");
        WorldDefinition hub = worlds.stream().filter(w -> w.id.equals("habi_train_survive:hub")).findFirst().orElseThrow();
        if (!hub.enabled || !"hub".equals(hub.kind)) throw new IllegalArgumentException("默认主城必须启用且 kind=hub");
        for (WorldDefinition w : worlds) if (w.enabled && w.portals)
            for (String id : List.of(w.overworld, w.nether, w.end))
                if (!ids.contains(id)) throw new IllegalArgumentException(w.id + " 传送门目标未登记: " + id);
    }
}
