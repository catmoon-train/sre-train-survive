package io.wifi.habitrainsurvive.world;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import java.util.Set;

public final class WorldDefinition {
    public String id, name;
    public String preset = "normal", kind = "survival", permission = "";
    public boolean enabled = true, open = true;
    public Long seed;
    public int playerLimit = 0;
    public double entryFee = 0;
    public GlobalPosInfo spawn;
    public double borderSize = 20000;
    public int rtpMin = 128, rtpMax = 3000, rtpAttempts = 24;
    public boolean pvp = true, build = true, interact = true, damage = true, hunger = true;
    public boolean mobs = true, mobGrief = true, explosions = true, fireSpread = true, fluids = true, pistons = true;
    public boolean allowClaims = true, allowShops = true, weather = true, portals = true;
    public long fixedTime = -1, resetIntervalHours = 0;
    public String difficulty = "normal";
    public String overworld = "minecraft:overworld", nether = "minecraft:the_nether", end = "minecraft:the_end";
    /** Standard Anvil directory, relative to the dedicated server's imports directory. */
    public String importSource;

    public WorldDefinition() {}
    public WorldDefinition(String id, String name, String preset, String kind) {
        this.id = id; this.name = name; this.preset = preset; this.kind = kind;
    }
    public boolean managed() { return id != null && id.startsWith("habi_train_survive:"); }
    public boolean resource() { return managed() && "resource".equals(kind); }
    public void validate() {
        validateId(id);
        if (name == null || name.isBlank() || name.length() > 64) fail("name 必须为1至64字符");
        if (!Set.of("normal", "nether", "end", "flat", "void").contains(preset)) fail("preset 无效");
        if (!Set.of("survival", "resource", "hub").contains(kind)) fail("kind 无效");
        if ("resource".equals(kind) && !managed()) fail("只有本核心维度允许标为资源世界");
        if (permission == null || !permission.matches("[a-zA-Z0-9_.*-]*")) fail("permission 无效");
        if (playerLimit < 0 || playerLimit > 100000) fail("playerLimit 超出范围");
        if (!Double.isFinite(entryFee) || entryFee < 0 || entryFee > 1e12) fail("entryFee 无效");
        if (!Double.isFinite(borderSize) || borderSize < 64 || borderSize > 59999968) fail("borderSize 超出范围");
        if (rtpMin < 0 || rtpMax <= rtpMin || rtpMax > 29000000 || rtpAttempts < 1 || rtpAttempts > 100) fail("rtp 范围/尝试次数无效");
        if (fixedTime < -1 || fixedTime > 24000 || resetIntervalHours < 0 || resetIntervalHours > 87600) fail("时间/重置间隔无效");
        if (!resource() && resetIntervalHours != 0) fail("非资源世界不可定期重置");
        if (!Set.of("peaceful", "easy", "normal", "hard").contains(difficulty)) fail("difficulty 无效");
        validateId(overworld); validateId(nether); validateId(end);
        if (spawn != null && (!id.equals(spawn.dimension) || !Double.isFinite(spawn.x) || !Double.isFinite(spawn.y)
                || !Double.isFinite(spawn.z) || !Float.isFinite(spawn.yaw) || !Float.isFinite(spawn.pitch)
                || Math.abs(spawn.x) > 29999984 || Math.abs(spawn.z) > 29999984 || spawn.y < -64 || spawn.y > 2048)) fail("spawn 无效");
        if (importSource != null && (importSource.isBlank() || importSource.contains("..") || importSource.contains(":")
                || importSource.startsWith("/") || importSource.contains("\\"))) fail("importSource 必须是 imports 内的相对路径");
    }
    public static void validateId(String id) {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("无效世界ID: " + id);
        String[] parts = id.split(":", 2);
        validateSegment(parts[0], id);
        for (String part : parts[1].split("/", -1)) validateSegment(part, id);
    }
    private static void validateSegment(String part, String id) {
        String stem = part.split("\\.", 2)[0];
        // Windows aliases device stems even with extensions, and strips trailing dots.
        if (part.isEmpty() || part.endsWith(".") || stem.matches("con|prn|aux|nul|com[1-9]|lpt[1-9]"))
            throw new IllegalArgumentException("世界ID含非法路径: " + id);
    }
    private void fail(String reason) { throw new IllegalArgumentException("worlds[" + id + "]." + reason); }
}
