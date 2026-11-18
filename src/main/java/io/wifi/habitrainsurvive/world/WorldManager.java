package io.wifi.habitrainsurvive.world;

import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.config.CoreConfigManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.hub.HubManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.security.CombatTagManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public final class WorldManager {
    public static final String HUB = "habi_train_survive:hub";
    private static final WorldManager INSTANCE = new WorldManager();
    private WorldConfig desired = WorldConfig.defaults(), active = WorldConfig.defaults();
    private final Set<String> startupEnabled = new HashSet<>();
    private final Map<String, WorldDefinition> retiredDefinitions = new HashMap<>();
    private final Map<String, ManagedLevelData> levelData = new HashMap<>();
    private WorldState state = new WorldState();
    private Path configFile, stateFile, saveRoot;
    private MinecraftServer server;
    private boolean prepared;
    private final ThreadLocal<Integer> internal = ThreadLocal.withInitial(() -> 0);
    private String lastError = "";
    private WorldManager() {}
    public static WorldManager get() { return INSTANCE; }
    public String lastError() { return lastError; }
    public MinecraftServer server() { return server; }
    public Path saveRoot() { return saveRoot; }
    public Collection<WorldDefinition> all() { return active.worlds.stream().filter(d -> !WorldConfig.excluded(d)).toList(); }
    public WorldConfig configuredCopy() { return copy(desired); }
    public boolean restartPending(String id) {
        WorldDefinition configured = desired.worlds.stream().filter(w -> w.id.equals(id)).findFirst().orElse(null);
        WorldDefinition running = definition(id);
        return configured == null || running == null || configured.enabled != running.enabled
                || !Objects.equals(configured.preset, running.preset) || !Objects.equals(configured.seed, running.seed)
                || !Objects.equals(configured.importSource, running.importSource);
    }
    private static WorldConfig copy(WorldConfig c) { return CoreConfigManager.GSON.fromJson(CoreConfigManager.GSON.toJson(c), WorldConfig.class); }
    public void loadConfiguration(Path file) throws IOException {
        configFile = file;
        retiredDefinitions.clear();
        desired = readConfiguration();
        active = copy(desired);
        startupEnabled.clear();
        for (WorldDefinition d : active.worlds) if (d.enabled) startupEnabled.add(d.id);
    }
    private WorldConfig readConfiguration() throws IOException {
        boolean[] migrated = {false};
        WorldConfig config = CoreConfigManager.load(configFile, WorldConfig.class, WorldConfig.defaults(), candidate -> {
            if (candidate.worlds != null) for (WorldDefinition d : candidate.worlds)
                if (WorldConfig.excluded(d)) retiredDefinitions.put(d.id, d);
            migrated[0] = candidate.removeExcludedWorlds();
            migrated[0] |= candidate.ensurePlotWorld();
            candidate.validate();
        });
        if (migrated[0]) CoreConfigManager.write(configFile, config);
        return config;
    }
    public void reload() throws IOException {
        WorldConfig next = readConfiguration();
        applyConfiguration(next);
    }
    public void update(Consumer<WorldConfig> change) throws IOException {
        WorldConfig next = copy(desired); change.accept(next); next.validate();
        CoreConfigManager.write(configFile, next); applyConfiguration(next);
    }
    private void applyConfiguration(WorldConfig next) {
        WorldConfig running = copy(next);
        for (WorldDefinition d : running.worlds) {
            WorldDefinition old = definition(d.id);
            if (old != null) { d.enabled = old.enabled; d.preset = old.preset; d.seed = old.seed; d.importSource = old.importSource; }
            else d.enabled = false;
        }
        for (WorldDefinition old : active.worlds)
            if (!WorldConfig.excluded(old) && running.worlds.stream().noneMatch(w -> w.id.equals(old.id))) running.worlds.add(old);
        desired = next; active = running;
        for (ManagedLevelData data : levelData.values()) data.refreshRules();
        lastError = "";
    }
    public void prepare(MinecraftServer server) {
        if (prepared && this.server == server) return;
        this.server = server; levelData.clear(); SafeTeleportService.get().clear();
        saveRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        stateFile = saveRoot.resolve("data/habi_world_state.json");
        try {
            state = CoreConfigManager.load(stateFile, WorldState.class, new WorldState(), WorldState::validate);
            if (!state.worlds.containsKey(HUB) && java.nio.file.Files.isDirectory(WorldPaths.dimension(saveRoot, HUB).resolve("region"))) {
                state.world(HUB).hubBuilt = true; state.world(HUB).imported = true;
            }
            WorldConfig recovery = copy(active);
            for (var entry : state.worlds.entrySet()) {
                String id = entry.getKey();
                if (!WorldConfig.excludedId(id) && !retiredDefinitions.containsKey(id)) continue;
                WorldState.Runtime retired = entry.getValue();
                if (retired.resetBackup == null) { retired.pendingReset = false; continue; }
                WorldDefinition definition = retiredDefinitions.get(id);
                if (definition == null) {
                    String preset = switch (id) {
                        case "habi_train_survive:resource" -> "normal";
                        case "habi_train_survive:resource_nether" -> "nether";
                        case "habi_train_survive:resource_end" -> "end";
                        default -> throw new IOException("已移除维度存在无法识别的中断事务，请保留日志并恢复旧配置核对: " + id);
                    };
                    definition = new WorldDefinition(id, "旧维度事务恢复", preset, "resource");
                }
                recovery.worlds.add(definition);
            }
            ResourceResetManager.applyPending(saveRoot, recovery, state, this::saveStrict);
            WorldImporter.applyImports(saveRoot, Path.of("").toAbsolutePath(), active, state, this::saveStrict);
            for (WorldDefinition d : active.worlds) state.world(d.id);
            saveStrict(); prepared = true;
        } catch (IOException e) { throw new IllegalStateException("世界预处理失败，拒绝打开区块文件以保护存档", e); }
    }
    public void started(MinecraftServer server) {
        this.server = server;
        for (WorldDefinition d : all()) {
            ServerLevel level = level(d.id);
            if (d.enabled && level == null) throw new IllegalStateException("已启用的维度未加载: " + d.id);
            if (d.managed() && level != null) {
                level.getWorldBorder().setCenter(0, 0); level.getWorldBorder().setSize(d.borderSize);
            }
        }
        HabiTrainSurvive.LOGGER.info("多世界已加载: {}", String.join(", ", all().stream().filter(w -> isLoaded(w.id)).map(w -> w.id).toList()));
    }
    public void stopped() { SafeTeleportService.get().clear(); prepared = false; server = null; levelData.clear(); }
    public void saveStrict() throws IOException { if (stateFile != null) CoreConfigManager.writeAtomic(stateFile, state); }
    public void save() {
        try { saveStrict(); } catch (IOException e) { lastError = e.getMessage(); HabiTrainSurvive.LOGGER.error("世界状态保存失败", e); }
    }
    public WorldState.Runtime runtime(String id) { return state.world(id); }
    public void registerData(String id, ManagedLevelData data) { levelData.put(id, data); }
    public boolean startupEnabled(String id) {
        return !WorldConfig.excludedId(id) && (!id.startsWith("habi_train_survive:") || startupEnabled.contains(id));
    }
    public WorldDefinition definition(String id) {
        if (id == null) return null;
        for (WorldDefinition d : active.worlds) if (d.id.equals(id)) return d;
        List<WorldDefinition> matches = active.worlds.stream().filter(w -> w.id.split(":", 2)[1].equals(id)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
    public String displayName(String id) { WorldDefinition d = definition(id); return d == null ? id : d.name; }
    public ServerLevel level(String id) {
        if (server == null || id == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }
    public boolean isLoaded(String id) { WorldDefinition d = definition(id); return level(d == null ? id : d.id) != null; }
    public long generation(String id) { WorldDefinition d = definition(id); return d != null && d.resource() ? runtime(d.id).generation : 0; }
    public long seed(String id, long vanilla) {
        WorldDefinition d = definition(id); if (d == null || !d.managed()) return vanilla;
        long base = d.seed == null ? vanilla ^ ((long) id.hashCode() * 0x9e3779b97f4a7c15L) : d.seed;
        long generation = generation(id);
        return generation == 0 ? base : base ^ (generation * 0x9e3779b97f4a7c15L);
    }
    public boolean current(GlobalPosInfo pos) { return pos != null && !WorldConfig.excludedId(pos.dimension) && pos.generation == generation(pos.dimension); }
    public boolean internal() { return internal.get() > 0; }
    public void recover(Runnable action) { internal.set(internal.get() + 1); try { action.run(); } finally { internal.set(internal.get() - 1); } }
    public String entryProblem(ServerPlayer player, String id, boolean checkTravel) {
        WorldDefinition d = definition(id);
        if (level(id) == null) return "目标世界未加载或已移除";
        if (d != null && runtime(id).pendingReset) return "资源世界已进入重置队列，请等待服务器重启";
        boolean admin = PermissionManager.get().hasPermission(player, "habi.world.admin");
        if (d != null && !admin) {
            if (!d.enabled || !d.open) return "该世界暂未开放";
            if (!d.permission.isBlank() && !PermissionManager.get().hasPermission(player, d.permission)) return "没有进入该世界的权限";
            if (d.playerLimit > 0 && !player.serverLevel().dimension().location().toString().equals(id)
                    && level(id).players().size() >= d.playerLimit) return "世界人数已满";
            if (!player.serverLevel().dimension().location().toString().equals(id) && !EconomyManager.get().hasBalance(player.getUUID(), d.entryFee)) return "余额不足以支付世界进入费";
        }
        if (checkTravel) {
            var profile = CmiManager.get().getOrCreateProfile(player);
            if (profile.isJailed()) return "服刑期间禁止传送";
            if (profile.frozen) return "冻结期间禁止传送";
            if (CombatTagManager.get().isInCombat(player)) return "战斗期间禁止传送";
        }
        return null;
    }
    public boolean guard(ServerPlayer player, ServerLevel target) {
        if (internal()) return true;
        String problem = entryProblem(player, target.dimension().location().toString(), true);
        if (problem != null) { MessageUtil.sendError(player, "世界通行", problem); return false; }
        return true;
    }
    public void finishTravel(ServerPlayer player, GlobalPosInfo origin) {
        if (internal() || origin == null) return;
        String id = player.serverLevel().dimension().location().toString();
        WorldDefinition d = definition(id);
        if (!id.equals(origin.dimension) && d != null && d.entryFee > 0
                && !PermissionManager.get().hasPermission(player, "habi.world.admin"))
            EconomyManager.get().withdraw(player.getUUID(), d.entryFee, "World entry " + id);
        CmiManager.get().getOrCreateProfile(player).lastLocation = origin;
        state.lastVisits.put(player.getUUID(), new WorldState.Visit(id, generation(id)));
    }
    public boolean teleport(ServerPlayer player, String requested) {
        WorldDefinition d = definition(requested);
        if (d == null) { MessageUtil.sendError(player, "世界导航", "未知世界: " + requested); return false; }
        if (d.preset.equals("nether") || d.preset.equals("end")) return QuickDimensionTravel.to(player, d.id);
        String problem = entryProblem(player, d.id, true);
        if (problem != null) { MessageUtil.sendError(player, "世界导航", problem); return false; }
        TeleportWarmupManager.get().scheduleTeleport(player, d.name, () -> SafeTeleportService.get().spawn(player, d.id));
        return true;
    }
    public boolean rtp(ServerPlayer player, String requested) {
        WorldDefinition d = definition(requested);
        if (d == null || "hub".equals(d.kind) || "void".equals(d.preset)) { MessageUtil.sendError(player, "随机传送", "此世界不支持随机传送"); return false; }
        String problem = entryProblem(player, d.id, true);
        if (problem != null) { MessageUtil.sendError(player, "随机传送", problem); return false; }
        var profile = CmiManager.get().getOrCreateProfile(player);
        long cooldown = CmiManager.get().getRtpCooldownForPlayer(player.getUUID()) * 1000L;
        if (!player.hasPermissions(2) && System.currentTimeMillis() - profile.lastRtpTimestamp < cooldown) {
            MessageUtil.sendError(player, "随机传送", "冷却中，请等待 " + Math.max(1, (cooldown - System.currentTimeMillis() + profile.lastRtpTimestamp) / 1000) + " 秒"); return false;
        }
        TeleportWarmupManager.get().scheduleTeleport(player, d.name + " 荒野", () -> SafeTeleportService.get().rtp(player, d.id));
        return true;
    }
    public GlobalPosInfo spawn(String id) {
        WorldDefinition d = definition(id); if (d == null) return null;
        GlobalPosInfo p = d.spawn != null && current(d.spawn) ? d.spawn : runtime(id).spawn;
        if (p == null || !current(p)) return null;
        return new GlobalPosInfo(id, p.x, p.y, p.z, p.yaw, p.pitch);
    }
    public boolean assetAllowed(ServerPlayer player, String id, boolean shop) {
        if (!shop && io.wifi.habitrainsurvive.plot.PlotGrid.WORLD.equals(id)) {
            MessageUtil.sendError(player, "地皮", "地皮世界请使用 /plot 购买，不能重复圈地或建立城镇"); return false;
        }
        WorldDefinition d = definition(id);
        if (d == null || (shop ? d.allowShops : d.allowClaims)) return true;
        MessageUtil.sendError(player, "世界规则", "本世界不允许建立永久" + (shop ? "商店" : "领地或城镇")); return false;
    }
    public void queueReset(String id) throws IOException {
        WorldDefinition d = definition(id);
        if (WorldConfig.excluded(d)) throw new IOException("该世界已停用，不再接受重置");
        if (d == null || !ResourceResetManager.eligible(d)) throw new IOException("只能重置本核心资源世界");
        WorldState.Runtime r = runtime(d.id); r.pendingReset = true; saveStrict();
        TeleportWarmupManager.get().cancelAll("资源世界进入重置队列"); SafeTeleportService.get().cancelWorld(d.id);
        CmiManager.get().cancelAllTpa();
        if (level(d.id) != null) for (ServerPlayer p : List.copyOf(level(d.id).players())) HubManager.get().rescue(p);
        server.getPlayerList().broadcastSystemMessage(Component.literal("§e[资源世界] " + d.name + " 已关闭并计划于下次重启备份重置，生存世界不受影响。"), false);
    }
    public void tick(MinecraftServer server) {
        for (WorldDefinition d : all()) {
            if (!isLoaded(d.id)) continue;
            if (d.managed()) {
                ServerLevel level = level(d.id);
                if (level.getWorldBorder().getSize() != d.borderSize) level.getWorldBorder().setSize(d.borderSize);
                if (!d.weather) level.setWeatherParameters(6000, 0, false, false);
                if (d.fixedTime >= 0) level.setDayTime(d.fixedTime);
                else runtime(d.id).dayTime += 20;
            }
            WorldState.Runtime r = runtime(d.id);
            if (d.resource() && d.resetIntervalHours > 0 && !r.pendingReset
                    && System.currentTimeMillis() - r.lastReset >= d.resetIntervalHours * 3_600_000L) {
                try { queueReset(d.id); } catch (IOException e) { lastError = e.getMessage(); HabiTrainSurvive.LOGGER.error("无法计划资源重置", e); }
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String id = p.serverLevel().dimension().location().toString();
            state.lastVisits.put(p.getUUID(), new WorldState.Visit(id, generation(id)));
        }
    }
    public boolean staleLogin(ServerPlayer player) {
        WorldState.Visit visit = state.lastVisits.get(player.getUUID());
        String id = player.serverLevel().dimension().location().toString();
        return visit != null && (WorldConfig.excludedId(visit.dimension()) || level(visit.dimension()) == null
                || visit.generation() != generation(visit.dimension()))
                || entryProblem(player, id, false) != null;
    }
}
