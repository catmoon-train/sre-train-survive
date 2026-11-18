package io.wifi.habitrainsurvive.onboarding;

import com.mojang.brigadier.CommandDispatcher;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.gift.GiftManager;
import io.wifi.habitrainsurvive.gift.GiftPackage;
import io.wifi.habitrainsurvive.presentation.ConfigFiles;
import io.wifi.habitrainsurvive.presentation.PresentationManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** All methods, including lifecycle persistence, are called on the server thread. */
public final class OnboardingManager {
    private static final OnboardingManager INSTANCE = new OnboardingManager();
    private static final UUID SYSTEM = new UUID(0, 0);
    private OnboardingConfig config = new OnboardingConfig();
    private OnboardingState state = new OnboardingState();
    private Path configFile;
    private Path stateFile;
    private Path giftFile;
    private boolean loaded;
    private boolean registered;
    private String configError = "";
    private final Map<UUID, Integer> pendingJoins = new HashMap<>();

    private OnboardingManager() { }
    public static OnboardingManager get() { return INSTANCE; }

    /** Call AFTER CMI and GiftManager.load, BEFORE players can connect. Existing UUIDs are a startup snapshot. */
    public void load(Path configFile, Path stateFile, Set<UUID> existingPlayers) {
        this.loaded = false;
        this.configFile = configFile;
        this.stateFile = stateFile;
        this.giftFile = stateFile.resolveSibling("gifts.json");
        pendingJoins.clear();
        reload();
        try {
            OnboardingState candidate = Files.exists(stateFile) ? ConfigFiles.read(stateFile, OnboardingState.class) : new OnboardingState();
            candidate.validate();
            candidate.seedExisting(Set.copyOf(existingPlayers));
            this.state = candidate;
            persist();
            // Recover a crash between gift-file commit and onboarding-state commit before any claims are possible.
            for (var entry : state.players.entrySet()) {
                if (!entry.getValue().pendingGifts.isEmpty()) deliverPending(entry.getKey(), entry.getValue());
            }
            loaded = true;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot safely load onboarding state " + stateFile + "; original state must be repaired, not reset", e);
        }
    }

    public boolean reload() {
        if (configFile == null) return false;
        try {
            ConfigFiles.installDefault(configFile, "/defaults/onboarding.json");
            OnboardingConfig candidate = ConfigFiles.read(configFile, OnboardingConfig.class);
            candidate.validate();
            config = candidate;
            configError = "";
            return true;
        } catch (IOException | RuntimeException e) {
            configError = configFile + ": " + e.getMessage();
            HabiTrainSurvive.LOGGER.error("新手引导配置无效，保留上一份有效配置: {}", configError);
            return false;
        }
    }

    public String configError() { return configError; }
    OnboardingConfig config() { return config; }

    public void save() {
        if (!loaded) return;
        try { persist(); }
        catch (IOException e) { HabiTrainSurvive.LOGGER.error("新手引导状态保存失败: {}", stateFile, e); }
    }

    private void persist() throws IOException { ConfigFiles.write(stateFile, state); }

    public void registerEvents() {
        if (registered) return;
        registered = true;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> pendingJoins.put(handler.getPlayer().getUUID(), server.getTickCount() + 1));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> pendingJoins.remove(handler.getPlayer().getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<UUID> ready = pendingJoins.entrySet().stream().filter(e -> e.getValue() <= server.getTickCount()).map(Map.Entry::getKey).toList();
            for (UUID uuid : ready) {
                pendingJoins.remove(uuid);
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null) onJoin(player);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> pendingJoins.clear());
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("guide").executes(context -> { openGuide(context.getSource().getPlayerOrException()); return 1; }));
        dispatcher.register(Commands.literal("rules").executes(context -> { openRules(context.getSource().getPlayerOrException()); return 1; }));
    }

    public void openGuide(ServerPlayer player) { if (active(player)) GuideGui.open(player); }
    public void openRules(ServerPlayer player) { if (active(player)) GuideGui.openRules(player, 0); }

    public boolean isNewPlayer(UUID uuid) {
        OnboardingState.Progress p = state.players.get(uuid);
        return loaded && (p == null || (!p.existingPlayer && !p.welcomed));
    }

    private boolean active(ServerPlayer player) {
        return loaded && !player.hasDisconnected() && player.connection.isAcceptingMessages()
                && !CmiManager.get().getOrCreateProfile(player).isBanned();
    }

    public void onJoin(ServerPlayer player) {
        if (!active(player)) return;
        OnboardingState.Progress progress = state.players.computeIfAbsent(player.getUUID(), ignored -> new OnboardingState.Progress());
        try {
            // Persist first; neither an interrupted connection nor a failed write consumes a gift.
            if (!progress.welcomed) {
                progress.welcomed = true;
                try { persist(); }
                catch (IOException e) { progress.welcomed = false; throw e; }
                if (config.enabled && active(player)) previewWelcome(player);
            }
            if (!progress.existingPlayer && !progress.rewardDelivered && config.enabled) grantReward(player, progress);
        } catch (IOException | RuntimeException e) {
            HabiTrainSurvive.LOGGER.error("新手奖励暂缓，保留可重试状态: {}", player.getUUID(), e);
            if (active(player)) player.sendSystemMessage(Component.literal("§e新手奖励暂未发放，请稍后重新登录或联系管理员；不会消耗领取机会。"));
        }
    }

    public void previewWelcome(ServerPlayer player) {
        if (!active(player)) return;
        if (config.welcomeTitle) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(render(config.subtitle, player))));
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(render(config.title, player))));
        }
        if (!config.welcomeMessage.isBlank()) player.sendSystemMessage(Component.literal(render(config.welcomeMessage, player)));
        PresentationManager.get().playSound(player);
    }

    private static String render(String text, ServerPlayer player) { return text.replace("{player}", player.getGameProfile().getName()); }

    private void grantReward(ServerPlayer player, OnboardingState.Progress progress) throws IOException {
        if (!active(player)) return;
        if (!progress.pendingGifts.isEmpty()) {
            deliverPending(player.getUUID(), progress);
        } else if (!config.rewardEnabled) {
            progress.rewardDelivered = true;
            try { persist(); }
            catch (IOException e) { progress.rewardDelivered = false; throw e; }
            return;
        } else {
            var kit = CmiManager.get().getKits().get(config.rewardKit);
            if (kit == null || kit.serializedItems == null || kit.serializedItems.isEmpty()) throw new IOException("rewardKit: missing or empty CMI kit " + config.rewardKit);
            List<GiftPackage> gifts = new ArrayList<>();
            for (String serialized : kit.serializedItems) {
                var stack = ItemSerializer.deserialize(serialized, player.server.registryAccess());
                if (stack.isEmpty()) throw new IOException("rewardKit: invalid serialized item in " + config.rewardKit);
                if (gifts.size() + stack.getCount() > 4096) throw new IOException("rewardKit: exceeds 4096 individual items");
                var single = stack.copyWithCount(1);
                String item = ItemSerializer.serialize(single, player.server.registryAccess());
                if (item.isBlank()) throw new IOException("rewardKit: failed to serialize item");
                // Single-item parcels prevent GiftManager's partial-inventory-insertion retry from duplicating stacks.
                for (int i = 0; i < stack.getCount(); i++) {
                    gifts.add(new GiftPackage("onboarding:" + player.getUUID() + ":" + gifts.size(), SYSTEM, "新手向导", item,
                            single.getHoverName().getString(), 1, 0, "首次加入奖励 · " + config.rewardKit));
                }
            }
            progress.rewardKit = config.rewardKit;
            progress.pendingGifts = gifts;
            try { persist(); }
            catch (IOException e) { progress.pendingGifts = new ArrayList<>(); throw e; }
            deliverPending(player.getUUID(), progress);
        }
        CmiManager.get().getOrCreateProfile(player).kitCooldowns.put(progress.rewardKit, System.currentTimeMillis());
        if (active(player) && !config.rewardMessage.isBlank()) player.sendSystemMessage(Component.literal(config.rewardMessage));
    }

    private void deliverPending(UUID uuid, OnboardingState.Progress progress) throws IOException {
        RewardDelivery.commit(progress, GiftManager.get().getGifts(uuid), () -> GiftManager.get().save(giftFile), () -> {
            // Verify the legacy void save rather than treating an internally logged error as a successful commit.
            GiftManager.Storage disk = ConfigFiles.read(giftFile, GiftManager.Storage.class);
            Set<String> persisted = new HashSet<>();
            if (disk.playerGiftBoxes != null) for (GiftPackage gift : disk.playerGiftBoxes.getOrDefault(uuid, List.of())) persisted.add(gift.id);
            return persisted;
        }, this::persist);
    }

    boolean hasRead(UUID uuid, String step) {
        var progress = state.players.get(uuid);
        return progress != null && progress.readSteps.contains(step);
    }

    void markRead(ServerPlayer player, String step) {
        if (!active(player) || config.steps.stream().noneMatch(s -> s.id.equals(step))) return;
        var progress = state.players.computeIfAbsent(player.getUUID(), ignored -> new OnboardingState.Progress());
        if (!progress.readSteps.add(step)) return;
        try { persist(); }
        catch (IOException e) {
            progress.readSteps.remove(step);
            player.sendSystemMessage(Component.literal("§c指南进度保存失败，请稍后重试。"));
            HabiTrainSurvive.LOGGER.error("指南进度保存失败: {}", stateFile, e);
        }
    }
}
