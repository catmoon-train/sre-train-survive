package io.wifi.habitrainsurvive;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.config.CoreConfigManager;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.hub.*;
import io.wifi.habitrainsurvive.menu.*;
import io.wifi.habitrainsurvive.onboarding.OnboardingManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.presentation.PresentationManager;
import io.wifi.habitrainsurvive.security.Confirmations;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.world.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Wires the server suite without changing ownership of existing business managers. */
public final class CoreRuntime {
    private static boolean loaded;
    private static final Map<String, String> reloadErrors = new LinkedHashMap<>();
    private static Path dataRoot;
    private CoreRuntime() {}
    public static void registerEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (!ModuleConfig.isEnabled(ModuleId.NATURAL_SPAWNING)) return;
            try {
                NaturalSpawnManager.get().load(CoreConfigManager.directory().resolve("natural_spawning.json"));
            } catch (IOException e) {
                throw new IllegalStateException("自然刷新配置加载失败，原文件未被覆盖", e);
            }
        });
        HubManager.get().registerEvents();
        HubProtectionListener.register();
        PresentationManager.get().registerEvents();
        OnboardingManager.get().registerEvents();
        io.wifi.habitrainsurvive.plot.PlotProtection.register();
        MenuEditorGui.registerEvents();
    }
    public static void started(MinecraftServer server, Path dataDir) {
        try {
            Set<UUID> known = new HashSet<>(CmiManager.get().knownPlayers());
            Path players = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            if (Files.isDirectory(players)) try (var paths = Files.list(players)) {
                paths.filter(p -> p.getFileName().toString().endsWith(".dat")).forEach(p -> {
                    String name = p.getFileName().toString();
                    try { known.add(UUID.fromString(name.substring(0, name.length() - 4))); } catch (IllegalArgumentException ignored) { }
                });
            }
            if (ModuleConfig.isEnabled(ModuleId.WORLD)) WorldManager.get().started(server);
            dataRoot = dataDir;
            if (ModuleConfig.isEnabled(ModuleId.PLOT)) io.wifi.habitrainsurvive.plot.PlotManager.get().load(dataDir.resolve("plots.json"));
            if (ModuleConfig.isEnabled(ModuleId.PLAYER_SHOP)) io.wifi.habitrainsurvive.playershop.PlayerShopManager.get().load(dataDir.resolve("player_shops.json"));
            if (ModuleConfig.isEnabled(ModuleId.HUB)) HubManager.get().load(dataDir.resolve("hub.json"), known);
            if (ModuleConfig.isEnabled(ModuleId.MENU)) MenuManager.get().load(dataDir.resolve("menus.json"));
            if (ModuleConfig.isEnabled(ModuleId.PRESENTATION)) PresentationManager.get().load(dataDir.resolve("presentation.json"), dataDir.resolve("presentation_state.json"));
            if (ModuleConfig.isEnabled(ModuleId.ONBOARDING)) OnboardingManager.get().load(dataDir.resolve("onboarding.json"), dataDir.resolve("onboarding_state.json"), known);
            loaded = true;
            if (ModuleConfig.isEnabled(ModuleId.HUB)) HubManager.get().tick(server);
            HabiTrainSurvive.LOGGER.info("菜单、独立主城、多世界、新手与运营展示已按模块开关启动。已有玩家迁移: {}", known.size());
        } catch (IOException e) { throw new IllegalStateException("核心配置加载失败，原文件未被覆盖", e); }
    }
    public static void tick(MinecraftServer server) {
        if (!loaded) return;
        if (ModuleConfig.isEnabled(ModuleId.HUB)) HubManager.get().tick(server);
        SafeTeleportService.get().tick(server);
    }
    public static void tickSecond(MinecraftServer server) {
        if (!loaded) return;
        if (ModuleConfig.isEnabled(ModuleId.WORLD)) WorldManager.get().tick(server);
        if (ModuleConfig.isEnabled(ModuleId.PRESENTATION)) PresentationManager.get().tick(server);
    }
    public static void save() {
        if (!loaded) return;
        if (ModuleConfig.isEnabled(ModuleId.WORLD)) WorldManager.get().save();
        if (ModuleConfig.isEnabled(ModuleId.ONBOARDING)) OnboardingManager.get().save();
        if (ModuleConfig.isEnabled(ModuleId.PRESENTATION)) PresentationManager.get().save();
        if (ModuleConfig.isEnabled(ModuleId.PLAYER_SHOP)) io.wifi.habitrainsurvive.playershop.PlayerShopManager.get().save(dataRoot.resolve("player_shops.json"));
        if (ModuleConfig.isEnabled(ModuleId.PLOT)) {
            try { io.wifi.habitrainsurvive.plot.PlotManager.get().save(); }
            catch (IOException e) { HabiTrainSurvive.LOGGER.error("地皮数据保存失败", e); }
        }
    }
    public static void stopped() {
        loaded = false; dataRoot = null; WorldManager.get().stopped(); Confirmations.clear(); reloadErrors.clear();
        io.wifi.habitrainsurvive.plot.PlotManager.get().stopped();
        MenuEditorGui.clear();
        io.wifi.habitrainsurvive.help.CommandHelpCommands.clear();
    }
    @FunctionalInterface private interface Reload { void run() throws IOException; }
    private static void reload(List<String> errors, String module, Reload action) {
        try { action.run(); reloadErrors.remove(module); }
        catch (IOException | IllegalArgumentException e) {
            String error = module + ": " + e.getMessage(); reloadErrors.put(module, error); errors.add(error);
        }
    }
    /** 重载世界、主城、菜单、自然刷新、展示与新手引导配置；/habi reload 与 /habi reloadall 共用。 */
    public static List<String> reloadConfiguration() {
        List<String> errors = new ArrayList<>();
        reload(errors, "modules.json", ModuleConfig::reload);
        if (ModuleConfig.isEnabled(ModuleId.WORLD)) reload(errors, "worlds.json", () -> WorldManager.get().reload());
        if (ModuleConfig.isEnabled(ModuleId.HUB)) reload(errors, "hub.json", () -> HubManager.get().reload());
        if (ModuleConfig.isEnabled(ModuleId.MENU)) reload(errors, "menus.json", () -> MenuManager.get().reload());
        if (ModuleConfig.isEnabled(ModuleId.NATURAL_SPAWNING)) reload(errors, "natural_spawning.json", () -> NaturalSpawnManager.get().reload());
        if (ModuleConfig.isEnabled(ModuleId.PRESENTATION) && !PresentationManager.get().reload()) errors.add(PresentationManager.get().configError());
        if (ModuleConfig.isEnabled(ModuleId.ONBOARDING) && !OnboardingManager.get().reload()) errors.add(OnboardingManager.get().configError());
        return errors;
    }
    private static boolean admin(CommandSourceStack source) { return PermissionManager.get().hasPermission(source, "habi.admin", 2); }
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (ModuleConfig.isEnabled(ModuleId.WORLD)) WorldCommands.register(dispatcher);
        if (ModuleConfig.isEnabled(ModuleId.HUB)) HubCommands.register(dispatcher);
        if (ModuleConfig.isEnabled(ModuleId.MENU)) MenuCommands.register(dispatcher);
        if (ModuleConfig.isEnabled(ModuleId.ONBOARDING)) OnboardingManager.get().registerCommands(dispatcher);
        if (ModuleConfig.isEnabled(ModuleId.PRESENTATION)) PresentationManager.get().registerCommands(dispatcher);
        if (ModuleConfig.isEnabled(ModuleId.NATURAL_SPAWNING)) NaturalSpawnManager.get().registerCommands(dispatcher);
        if (ModuleConfig.isEnabled(ModuleId.PLOT)) io.wifi.habitrainsurvive.plot.PlotCommands.register(dispatcher);
        if (ModuleConfig.isEnabled(ModuleId.LANDMARK)) io.wifi.habitrainsurvive.landmark.LandmarkGui.register(dispatcher);
        if (ModuleConfig.isEnabled(ModuleId.PLAYER_SHOP)) io.wifi.habitrainsurvive.playershop.PlayerShopCommands.register(dispatcher);
        dispatcher.register(Commands.literal("habi")
                .executes(ctx -> {
                    if (!admin(ctx.getSource())) return io.wifi.habitrainsurvive.help.CommandHelpCommands.show(ctx.getSource(), "all", 1);
                    if (ctx.getSource().getPlayer() != null) AdminGui.open(ctx.getSource().getPlayer()); else WorldCommands.list(ctx.getSource()); return 1;
                })
                .then(Commands.literal("edit").requires(CoreRuntime::admin).executes(ctx -> { AdminGui.open(ctx.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("reload").requires(CoreRuntime::admin).executes(ctx -> {
                    List<String> errors = reloadConfiguration();
                    if (!errors.isEmpty()) { ctx.getSource().sendFailure(Component.literal("部分配置无效，相应模块保留有效配置：\n" + String.join("\n", errors))); return 0; }
                    ctx.getSource().sendSuccess(() -> Component.literal("运营配置已重载；世界创建/导入/启停仍需重启。"), true); return 1;
                }))
                .then(Commands.literal("reloadall").requires(CoreRuntime::admin).executes(ctx -> {
                    List<String> errors = ServerDataManager.reloadAll(ctx.getSource().getServer());
                    if (!errors.isEmpty()) {
                        ctx.getSource().sendFailure(Component.literal("本模组数据部分重载失败，失败模块继续使用内存中的有效数据：\n" + String.join("\n", errors)));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("已重载本模组全部数据文件（权限、经济、领地、城镇、CMI、商店、拍卖、礼包、兑换、抽奖、商城、墓碑、任务、称号、红包及运营配置）；世界创建/导入/启停仍需重启。"), true);
                    return 1;
                }))
                .then(Commands.literal("status").requires(CoreRuntime::admin).executes(ctx -> {
                    WorldCommands.list(ctx.getSource());
                    ctx.getSource().sendSuccess(() -> Component.literal("主城建造完成=" + WorldManager.get().runtime(WorldManager.HUB).hubBuilt
                            + "；世界错误=" + WorldManager.get().lastError() + "；引导错误=" + OnboardingManager.get().configError()
                            + "；展示错误=" + PresentationManager.get().configError() + "；重载错误=" + reloadErrors.values()), false); return 1;
                }))
                .then(Commands.literal("preview").requires(CoreRuntime::admin).executes(ctx -> { PresentationManager.get().preview(ctx.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("modules").requires(CoreRuntime::admin)
                        .executes(ctx -> listModules(ctx.getSource()))
                        .then(Commands.literal("gui").executes(ctx -> { AdminGui.openModules(ctx.getSource().getPlayerOrException()); return 1; }))
                        .then(Commands.literal("toggle").then(Commands.argument("module", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(ModuleId.values()).map(ModuleId::id), b))
                                .executes(ctx -> setModuleState(ctx.getSource(), StringArgumentType.getString(ctx, "module"), null))))
                        .then(Commands.literal("enable").then(Commands.argument("module", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(ModuleId.values()).map(ModuleId::id), b))
                                .executes(ctx -> setModuleState(ctx.getSource(), StringArgumentType.getString(ctx, "module"), Boolean.TRUE))))
                        .then(Commands.literal("disable").then(Commands.argument("module", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(ModuleId.values()).map(ModuleId::id), b))
                                .executes(ctx -> setModuleState(ctx.getSource(), StringArgumentType.getString(ctx, "module"), Boolean.FALSE))))));
        io.wifi.habitrainsurvive.help.CommandHelpCommands.register(dispatcher);
    }

    private static int listModules(CommandSourceStack source) {
        long enabled = Arrays.stream(ModuleId.values()).filter(ModuleConfig::isEnabled).count();
        source.sendSuccess(() -> Component.literal("=== Habi 模块开关状态 (" + enabled + "/" + ModuleId.values().length + ") ==="), false);
        for (ModuleId id : ModuleId.values()) {
            boolean state = ModuleConfig.isEnabled(id);
            source.sendSuccess(() -> Component.literal("[" + (state ? "§a开启" : "§c关闭") + "§r] §b" + id.id() + "§r (" + id.displayName() + ") - §7" + id.description()), false);
        }
        return 1;
    }

    private static int setModuleState(CommandSourceStack source, String rawId, Boolean explicitState) {
        ModuleId id = ModuleId.fromId(rawId);
        if (id == null) {
            source.sendFailure(Component.literal("未知模块 ID: " + rawId + "。请使用 /habi modules 查看可用模块。"));
            return 0;
        }
        try {
            boolean next = explicitState != null ? explicitState : !ModuleConfig.isEnabled(id);
            ModuleConfig.setEnabled(id, next);
            source.sendSuccess(() -> Component.literal("模块 [" + id.displayName() + " (" + id.id() + ")] 已设置为: " + (next ? "开启" : "关闭")
                    + "（监听器与定时任务已即时生效；指令注册变更需重启服务器生效）。"), true);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("保存 modules.json 失败: " + e.getMessage()));
            return 0;
        }
    }
}
