package io.wifi.habitrainsurvive.presentation;

import com.mojang.brigadier.CommandDispatcher;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.menu.MenuManager;
import io.wifi.habitrainsurvive.onboarding.OnboardingManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.world.WorldManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Facade. Main lifecycle calls tick once per second, load/save and command/event registration. */
public final class PresentationManager {
    private static final PresentationManager INSTANCE = new PresentationManager();
    private final AnnouncementManager announcements = new AnnouncementManager();
    private final SidebarManager sidebar = new SidebarManager();
    private final TabManager tab = new TabManager();
    private final Set<UUID> acceptedPlayers = new HashSet<>();
    private final Map<UUID, Long> previewUntil = new HashMap<>();
    private PresentationConfig config = new PresentationConfig();
    private PresentationState state = new PresentationState();
    private Path configFile;
    private Path stateFile;
    private boolean loaded;
    private boolean registered;
    private long lastTick = -20;
    private long nextRefresh;
    private String configError = "";

    private PresentationManager() { }
    public static PresentationManager get() { return INSTANCE; }

    public void load(Path configFile, Path stateFile) {
        loaded = false;
        this.configFile = configFile;
        this.stateFile = stateFile;
        clearTransient();
        reload();
        try {
            PresentationState candidate = Files.exists(stateFile) ? ConfigFiles.read(stateFile, PresentationState.class) : new PresentationState();
            candidate.validate();
            state = candidate;
            ConfigFiles.write(stateFile, state);
            loaded = true;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load presentation preferences " + stateFile + "; original state was not replaced", e);
        }
    }

    public boolean reload() {
        if (configFile == null) return false;
        try {
            ConfigFiles.installDefault(configFile, "/defaults/presentation.json");
            PresentationConfig candidate = ConfigFiles.read(configFile, PresentationConfig.class);
            candidate.validate();
            config = candidate;
            configError = "";
            announcements.reset();
            nextRefresh = 0;
            return true;
        } catch (IOException | RuntimeException e) {
            configError = configFile + ": " + e.getMessage();
            HabiTrainSurvive.LOGGER.error("运营展示配置无效，保留上一份有效配置: {}", configError);
            return false;
        }
    }

    public String configError() { return configError; }

    public void save() {
        if (!loaded) return;
        try { ConfigFiles.write(stateFile, state); }
        catch (IOException e) { HabiTrainSurvive.LOGGER.error("玩家展示偏好保存失败: {}", stateFile, e); }
    }

    public void registerEvents() {
        if (registered) return;
        registered = true;
        // The first tick after all JOIN handlers is the admission barrier: banned/disconnected players never get welcome broadcasts.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            acceptedPlayers.remove(uuid);
            sidebar.forget(uuid);
            tab.forget(uuid);
            previewUntil.remove(uuid);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (loaded && acceptedPlayers.remove(player.getUUID())) announcements.joinOrLeave(player, false, config, this);
            sidebar.forget(player.getUUID());
            tab.forget(player.getUUID());
            previewUntil.remove(player.getUUID());
        });
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> {
            if (!loaded || !config.joinLeaveEnabled || overlay || !(message.getContents() instanceof TranslatableContents content)) return true;
            // Suppress only vanilla connection notices; custom literal broadcasts and all other messages are untouched.
            return !Set.of("multiplayer.player.joined", "multiplayer.player.joined.renamed", "multiplayer.player.left").contains(content.getKey());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { clearTransient(); loaded = false; });
    }

    private void clearTransient() {
        acceptedPlayers.clear();
        previewUntil.clear();
        sidebar.clear();
        tab.clear();
        announcements.reset();
        lastTick = -20;
        nextRefresh = 0;
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var settings = Commands.literal("settings").executes(context -> { openSettings(context.getSource().getPlayerOrException()); return 1; });
        for (String option : new String[]{"sidebar", "announcements", "sounds"}) {
            settings.then(Commands.literal(option).executes(context -> { toggle(context.getSource().getPlayerOrException(), option); return 1; }));
        }
        dispatcher.register(settings);
    }

    /** Called once per second by the core; duplicate calls inside the same second are ignored. */
    public void tick(MinecraftServer server) {
        if (!loaded) return;
        long now = server.getTickCount();
        if (now >= lastTick && now - lastTick < 20) return;
        lastTick = now;
        boolean refresh = now >= nextRefresh;
        if (refresh) nextRefresh = now + config.refreshSeconds * 20L;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isConnected(player)) continue;
            boolean first = acceptedPlayers.add(player.getUUID());
            if (first) announcements.joinOrLeave(player, true, config, this);
            Long until = previewUntil.get(player.getUUID());
            boolean expired = until != null && now >= until;
            if (expired) previewUntil.remove(player.getUUID());
            if (first || refresh || expired) refresh(player);
        }
        announcements.tick(server, config, this);
    }

    private void refresh(ServerPlayer player) {
        if (!isConnected(player)) return;
        boolean preview = previewUntil.containsKey(player.getUUID());
        var preferences = preferences(player.getUUID());
        // Resolve context once per player/refresh instead of doing an economy lookup for every line.
        UnaryOperator<String> renderer = renderer(player);
        sidebar.update(player, config, preview || (config.sidebarEnabled && preferences.sidebar), renderer);
        tab.update(player, config, preview || config.tabEnabled, renderer);
    }

    public static boolean isConnected(ServerPlayer player) {
        return player != null && !player.hasDisconnected() && player.connection != null && player.connection.isAcceptingMessages()
                && !CmiManager.get().getOrCreateProfile(player).isBanned();
    }

    public PresentationState.Preferences preferences(UUID uuid) {
        var p = state.players.get(uuid);
        return p == null ? new PresentationState.Preferences(config.defaultSidebar, config.defaultAnnouncements, config.defaultSounds)
                : new PresentationState.Preferences(p.sidebar, p.announcements, p.sounds);
    }

    public void playSound(ServerPlayer player) {
        if (isConnected(player) && preferences(player.getUUID()).sounds) player.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 0.35F, 1.3F);
    }

    public String render(String template, ServerPlayer player) { return renderer(player).apply(template); }

    private UnaryOperator<String> renderer(ServerPlayer player) {
        String name = player.getGameProfile().getName();
        String balance = EconomyManager.get().format(EconomyManager.get().getBalance(player.getUUID()));
        String dimension = player.serverLevel().dimension().location().toString();
        String world = WorldManager.get().displayName(dimension);
        var rank = PermissionManager.get().getPlayerGroup(player.getUUID());
        String group = rank == null ? "default" : (rank.prefix == null || rank.prefix.isBlank() ? rank.name : rank.prefix);
        String online = Integer.toString(player.server.getPlayerCount());
        String max = Integer.toString(player.server.getMaxPlayers());
        return text -> text.replace("{player}", name).replace("{balance}", balance).replace("{world}", world)
                .replace("{dimension}", dimension).replace("{group}", group).replace("{online}", online).replace("{max}", max);
    }

    /** Intended for /habi preview: explicit permission, private preview, no reward/progress changes. */
    public void preview(ServerPlayer player) {
        if (!loaded || !isConnected(player)) return;
        if (!PermissionManager.get().hasPermission(player, "habi.presentation.preview")) {
            player.sendSystemMessage(Component.literal("§c没有预览权限 (habi.presentation.preview)。"));
            return;
        }
        previewUntil.put(player.getUUID(), player.server.getTickCount() + 300L);
        refresh(player);
        announcements.preview(player, config, this);
        OnboardingManager.get().previewWelcome(player);
        player.sendSystemMessage(Component.literal("§a展示预览持续 15 秒；不会更改你的偏好或发放奖励。"));
    }

    public void openSettings(ServerPlayer player) {
        if (!loaded || !isConnected(player)) return;
        var preferences = preferences(player.getUUID());
        var profile = CmiManager.get().getOrCreateProfile(player);
        Map<Integer, MenuGui.Button> slots = new HashMap<>();
        slots.put(10, new MenuGui.Button(MenuGui.icon(Items.PAINTING, "侧边栏: " + status(preferences.sidebar), "§7余额、世界、组别和菜单提示", "§7遇到其他服务器计分板时自动让位"), p -> { toggle(p, "sidebar"); openSettings(p); }));
        slots.put(12, new MenuGui.Button(MenuGui.icon(Items.PAPER, "公告: " + status(preferences.announcements), "§7控制轮播公告及本模块加入/离开消息"), p -> { toggle(p, "announcements"); openSettings(p); }));
        slots.put(14, new MenuGui.Button(MenuGui.icon(Items.NOTE_BLOCK, "提示音: " + status(preferences.sounds), "§7仅控制本模块提示音，不修改其他模组声音"), p -> { toggle(p, "sounds"); openSettings(p); }));
        slots.put(16, new MenuGui.Button(MenuGui.icon(Items.ENDER_PEARL, "接收 TPA: " + status(profile.tpaToggle), "§7复用现有 CMI 免打扰设置"), p -> toggleCmi(p, true)));
        slots.put(20, new MenuGui.Button(MenuGui.icon(Items.GOLD_INGOT, "接收转账: " + status(profile.payToggle), "§7复用现有收款免打扰设置"), p -> toggleCmi(p, false)));
        slots.put(22, new MenuGui.Button(MenuGui.icon(Items.ARROW, "返回主菜单"), p -> MenuManager.get().open(p, "main")));
        slots.put(26, new MenuGui.Button(MenuGui.icon(Items.BARRIER, "关闭"), ServerPlayer::closeContainer));
        MenuGui.openSlots(player, config.settingsTitle, 3, slots);
    }

    private static String status(boolean enabled) { return enabled ? "§a开启" : "§c关闭"; }

    private void toggle(ServerPlayer player, String option) {
        if (!loaded || !isConnected(player)) return;
        var old = state.players.get(player.getUUID());
        var next = preferences(player.getUUID());
        switch (option) {
            case "sidebar" -> next.sidebar = !next.sidebar;
            case "announcements" -> next.announcements = !next.announcements;
            case "sounds" -> next.sounds = !next.sounds;
            default -> throw new IllegalArgumentException("Unknown preference " + option);
        }
        state.players.put(player.getUUID(), next);
        try {
            ConfigFiles.write(stateFile, state);
        } catch (IOException e) {
            if (old == null) state.players.remove(player.getUUID()); else state.players.put(player.getUUID(), old);
            HabiTrainSurvive.LOGGER.error("玩家偏好保存失败: {}", stateFile, e);
            player.sendSystemMessage(Component.literal("§c设置保存失败，已保留原设置，请稍后重试。"));
            return;
        }
        previewUntil.remove(player.getUUID());
        refresh(player);
        playSound(player);
        player.sendSystemMessage(Component.literal("§a偏好已保存。"));
    }

    private void toggleCmi(ServerPlayer player, boolean tpa) {
        if (!loaded || !isConnected(player)) return;
        var profile = CmiManager.get().getOrCreateProfile(player);
        if (tpa) profile.tpaToggle = !profile.tpaToggle; else profile.payToggle = !profile.payToggle;
        CmiManager.get().save(stateFile.resolveSibling("cmi.json"));
        playSound(player);
        openSettings(player);
    }
}
