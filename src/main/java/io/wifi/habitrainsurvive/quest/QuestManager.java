package io.wifi.habitrainsurvive.quest;

import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-only quest runtime: random daily/weekly rotations, permanent chapter progress, and a
 * single event entry point shared by Fabric hooks and mixins.
 */
public final class QuestManager {
    private static final QuestManager INSTANCE = new QuestManager();
    private static final String MODULE = "任务";
    private static final String DEFAULT_RESOURCE = "/defaults/quests.json";

    public static final class Rotation {
        public String period = "";
        public List<String> quests = new ArrayList<>();
        public Rotation() { }
        Rotation(String period, List<String> quests) { this.period = period; this.quests = quests; }
    }

    public static final class PlayerState {
        public Map<String, Long> progress = new HashMap<>();
        public Map<String, Rotation> rotations = new HashMap<>();
        public Set<String> claimed = new HashSet<>();
        public Set<String> completed = new HashSet<>();
    }

    public static final class Storage {
        public Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    }

    private QuestCatalog catalog;
    private Storage storage = new Storage();
    private Path definitionsPath;
    private Path progressPath;
    private String catalogError = "";
    private boolean loaded;
    private boolean dirty;
    private int saveTicker;
    private boolean eventsRegistered;

    private QuestManager() { }

    public static QuestManager get() { return INSTANCE; }

    public synchronized void load(Path definitions, Path progress) {
        this.definitionsPath = definitions;
        this.progressPath = progress;
        try {
            installDefaults(definitions);
            this.catalog = QuestCatalog.parse(ServerDataManager.GSON, Files.readString(definitions, StandardCharsets.UTF_8));
            this.catalogError = "";
        } catch (Exception exception) {
            this.catalogError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            HabiTrainSurvive.LOGGER.error("任务定义加载失败，保留原文件并禁用任务内容: {}", this.catalogError);
            this.catalog = QuestCatalog.empty();
        }
        Storage read = ServerDataManager.readJson(progress, Storage.class, new Storage());
        this.storage = read == null ? new Storage() : read;
        if (this.storage.players == null) this.storage.players = new ConcurrentHashMap<>();
        this.loaded = true;
        this.dirty = false;
    }

    /** Replaces the running definitions only after the edited file passes validation. */
    public synchronized String reload() {
        if (definitionsPath == null) return "任务系统尚未初始化";
        try {
            QuestCatalog next = QuestCatalog.parse(ServerDataManager.GSON, Files.readString(definitionsPath, StandardCharsets.UTF_8));
            this.catalog = next;
            this.catalogError = "";
            return null;
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            HabiTrainSurvive.LOGGER.error("任务重载失败，继续使用旧定义: {}", message);
            return message;
        }
    }

    /**
     * 在线编辑器写回：先校验再原子替换 quests.json，最后才切换运行中的定义。
     * 校验失败或写盘失败时磁盘与内存都保持原状，不会留下半份任务文件。
     */
    public synchronized String replaceDefinitions(String json) {
        if (definitionsPath == null) return "任务系统尚未初始化";
        QuestCatalog next;
        try {
            next = QuestCatalog.parse(ServerDataManager.GSON, json);
        } catch (Exception exception) {
            return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
        Path parent = definitionsPath.toAbsolutePath().getParent();
        try {
            Files.createDirectories(parent);
            Path temp = definitionsPath.resolveSibling(definitionsPath.getFileName() + ".upload.tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, definitionsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            return "写入 quests.json 失败: " + exception.getMessage();
        }
        this.catalog = next;
        this.catalogError = "";
        return null;
    }

    private static void installDefaults(Path definitions) throws IOException {
        if (Files.exists(definitions)) {
            String existing = Files.readString(definitions, StandardCharsets.UTF_8);
            // The old build stored only player progress in quests.json; keep it instead of overwriting it.
            if (existing.contains("\"playerQuests\"")) {
                Path legacy = definitions.resolveSibling("quests_legacy_backup.json");
                Files.move(definitions, legacy, StandardCopyOption.REPLACE_EXISTING);
                HabiTrainSurvive.LOGGER.warn("已将旧版任务进度备份为 {}", legacy.getFileName());
            } else {
                return;
            }
        }
        Files.createDirectories(definitions.toAbsolutePath().getParent());
        try (InputStream stream = QuestManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (stream == null) throw new IOException("缺少内置任务模板 " + DEFAULT_RESOURCE);
            Files.writeString(definitions, new String(stream.readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
    }

    public synchronized void save(Path progress) {
        if (!loaded) return;
        Path target = progress != null ? progress : progressPath;
        if (target != null) ServerDataManager.writeJson(target, storage);
        dirty = false;
    }

    public synchronized void saveIfDirty() {
        if (dirty && progressPath != null) save(progressPath);
    }

    public QuestCatalog catalog() { return catalog; }
    public String catalogError() { return catalogError; }
    public boolean hasCatalog() { return ModuleConfig.isEnabled(ModuleId.QUEST) && catalog != null && loaded; }
    public Path definitionsPath() { return definitionsPath; }
    public Path progressPath() { return progressPath; }

    public synchronized PlayerState state(UUID uuid) {
        if (storage.players == null) storage.players = new ConcurrentHashMap<>();
        return storage.players.computeIfAbsent(uuid, id -> new PlayerState());
    }

    // ---------------------------------------------------------------- rotations

    private ZoneId zone() {
        try { return ZoneId.of(catalog.settings().timeZone); }
        catch (RuntimeException exception) { return ZoneId.of("Asia/Shanghai"); }
    }

    public String dailyPeriod() { return LocalDate.now(zone()).toString(); }

    public String weeklyPeriod() {
        LocalDate now = LocalDate.now(zone());
        WeekFields weeks = WeekFields.ISO;
        int year = now.get(weeks.weekBasedYear());
        int week = now.get(weeks.weekOfWeekBasedYear());
        return year + "-W" + String.format(Locale.ROOT, "%02d", week);
    }

    public synchronized List<QuestDefinition.Quest> rotationQuests(ServerPlayer player, String kind) {
        if (!hasCatalog()) return List.of();
        PlayerState st = state(player.getUUID());
        Rotation rotation = ensureRotation(st, player.getUUID(), kind);
        List<QuestDefinition.Quest> quests = new ArrayList<>();
        for (String id : rotation.quests) {
            QuestDefinition.Quest quest = catalog.quest(id);
            if (quest != null) quests.add(quest);
        }
        return quests;
    }

    private Rotation ensureRotation(PlayerState st, UUID uuid, String kind) {
        boolean daily = "daily".equals(kind);
        String period = daily ? dailyPeriod() : weeklyPeriod();
        Rotation current = st.rotations.get(kind);
        if (current != null && period.equals(current.period)) return current;
        Set<String> old = current == null ? Set.of() : new HashSet<>(current.quests);
        List<QuestDefinition.Quest> templates = daily ? catalog.daily() : catalog.weekly();
        int count = Math.min(daily ? catalog.settings().dailyCount : catalog.settings().weeklyCount, templates.size());
        List<String> ids = new ArrayList<>();
        for (QuestDefinition.Quest quest : templates) ids.add(quest.id);
        List<String> picked = pickRotation(uuid, period, kind, ids, count);
        for (String id : old) {
            for (String key : new ArrayList<>(st.progress.keySet())) if (key.startsWith(id + "#")) st.progress.remove(key);
            st.claimed.remove(id);
        }
        Rotation next = new Rotation(period, picked);
        st.rotations.put(kind, next);
        dirty = true;
        return next;
    }

    /** Deterministic per player, period and kind; identical inputs always offer identical quests. */
    static List<String> pickRotation(UUID uuid, String period, String kind, List<String> templateIds, int count) {
        List<String> ids = new ArrayList<>(templateIds);
        Random random = new Random(uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits()
                ^ ((long) period.hashCode() << 17) ^ kind.hashCode() * 0x9E3779B9L);
        java.util.Collections.shuffle(ids, random);
        return new ArrayList<>(ids.subList(0, Math.max(0, Math.min(count, ids.size()))));
    }


    public synchronized List<QuestDefinition.Quest> activeQuests(ServerPlayer player) {
        if (!hasCatalog()) return List.of();
        PlayerState st = state(player.getUUID());
        List<QuestDefinition.Quest> result = new ArrayList<>(rotationQuests(player, "daily"));
        result.addAll(rotationQuests(player, "weekly"));
        for (QuestDefinition.Chapter chapter : catalog.chapters()) {
            for (QuestDefinition.Quest quest : chapter.quests) {
                if (catalog.unlocked(quest.id, st.completed)) result.add(quest);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- progress

    public static String key(String questId, int index) { return questId + "#" + index; }

    public synchronized long progress(UUID uuid, String questId, int index) {
        return state(uuid).progress.getOrDefault(key(questId, index), 0L);
    }

    public synchronized boolean taskComplete(UUID uuid, QuestDefinition.Quest quest, int index) {
        return progress(uuid, quest.id, index) >= quest.tasks.get(index).amount;
    }

    public synchronized boolean questComplete(UUID uuid, QuestDefinition.Quest quest) {
        for (int i = 0; i < quest.tasks.size(); i++) if (!taskComplete(uuid, quest, i)) return false;
        return true;
    }

    public synchronized boolean claimed(UUID uuid, String questId) {
        return state(uuid).claimed.contains(questId);
    }

    public synchronized boolean completedChapterQuest(UUID uuid, String questId) {
        return state(uuid).completed.contains(questId);
    }

    public synchronized boolean unlocked(UUID uuid, String questId) {
        return catalog != null && catalog.unlocked(questId, state(uuid).completed);
    }

    public synchronized boolean isRotationQuest(UUID uuid, String questId) {
        PlayerState st = state(uuid);
        Rotation daily = ensureRotation(st, uuid, "daily");
        Rotation weekly = ensureRotation(st, uuid, "weekly");
        return daily.quests.contains(questId) || weekly.quests.contains(questId);
    }

    /** Increments every matching active task. Returns whether anything changed. */
    public synchronized boolean event(ServerPlayer player, String triggerType, String registryId, long amount) {
        if (!hasCatalog() || player == null || amount <= 0) return false;
        PlayerState st = state(player.getUUID());
        boolean changed = false;
        for (QuestDefinition.Quest quest : activeQuests(player)) {
            boolean wasComplete = questComplete(player.getUUID(), quest);
            for (int i = 0; i < quest.tasks.size(); i++) {
                QuestDefinition.Task task = quest.tasks.get(i);
                if (!task.type.equals(triggerType)) continue;
                if (!matches(task, registryId)) continue;
                String key = key(quest.id, i);
                long current = st.progress.getOrDefault(key, 0L);
                if (current >= task.amount) continue;
                long next = Math.min(task.amount, current + amount);
                st.progress.put(key, next);
                changed = true;
                if (next >= task.amount) {
                    MessageUtil.sendInfo(player, MODULE, "目标完成：" + describe(task) + "（" + quest.label() + "）");
                }
            }
            if (changed && !wasComplete && questComplete(player.getUUID(), quest)) {
                MessageUtil.sendSuccess(player, MODULE, "任务已完成，可领取奖励：" + quest.label());
                MessageUtil.playDing(player);
            }
        }
        if (changed) dirty = true;
        return changed;
    }

    private boolean matches(QuestDefinition.Task task, String registryId) {
        String spec = task.target == null ? "" : task.target.trim();
        if (spec.isEmpty()) return false;
        if ("*".equals(spec)) return true;
        if (registryId == null || registryId.isBlank()) return false;
        if (spec.startsWith("#")) return tagMatches(task.type, spec.substring(1), registryId);
        return spec.equals(registryId);
    }

    private boolean tagMatches(String triggerType, String tagText, String registryId) {
        ResourceLocation tagId = ResourceLocation.tryParse(tagText);
        ResourceLocation id = ResourceLocation.tryParse(registryId);
        if (tagId == null || id == null) return false;
        QuestTrigger.Type type = QuestTrigger.types().get(triggerType);
        if (type == null) return false;
        try {
            return switch (type.targetKind()) {
                case ITEM -> {
                    Item item = BuiltInRegistries.ITEM.get(id);
                    yield item != null && item != Items.AIR
                            && item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tagId));
                }
                case BLOCK -> {
                    Block block = BuiltInRegistries.BLOCK.get(id);
                    yield block != null && block.builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, tagId));
                }
                case ENTITY -> {
                    EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(id);
                    yield entityType != null
                            && entityType.builtInRegistryHolder().is(TagKey.create(Registries.ENTITY_TYPE, tagId));
                }
                case NONE -> false;
            };
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static String describe(QuestDefinition.Task task) {
        String name = task.display == null || task.display.isBlank() ? task.target : task.display;
        if (task.type.equals("online_time")) return "在线 " + task.amount + " 秒";
        return QuestTrigger.label(task.type) + " " + (name == null || name.isBlank() ? "*" : name) + " x" + task.amount;
    }

    // ---------------------------------------------------------------- hooks

    public void registerEvents() {
        if (eventsRegistered) return;
        eventsRegistered = true;

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return;
            onBlockMined(serverPlayer, state);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getEntity() instanceof ServerPlayer killer)) return;
            onKill(killer, entity);
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer && hand == InteractionHand.MAIN_HAND) {
                ItemStack stack = player.getItemInHand(hand);
                if (!stack.isEmpty()) event(serverPlayer, "use_item", idOf(BuiltInRegistries.ITEM.getKey(stack.getItem())), 1);
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });
    }

    public void onBlockMined(ServerPlayer player, BlockState state) {
        event(player, "mine_block", idOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())), 1);
    }

    public void onBlockPlaced(ServerPlayer player, BlockState state) {
        event(player, "place_block", idOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())), 1);
    }

    public void onKill(ServerPlayer player, LivingEntity killed) {
        event(player, "kill_entity", idOf(BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType())), 1);
    }

    public void onItemObtained(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        event(player, "obtain_item", idOf(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount());
    }

    public void onItemCrafted(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        event(player, "craft_item", idOf(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount());
    }

    private static String idOf(ResourceLocation location) { return location == null ? "" : location.toString(); }

    /** Adds one second to online-time tasks and periodically flushes progress. */
    public synchronized void tick(MinecraftServer server) {
        if (!hasCatalog() || server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) event(player, "online_time", "", 1);
        if (++saveTicker >= 30) {
            saveTicker = 0;
            saveIfDirty();
        }
    }

    // ---------------------------------------------------------------- submit and claim

    public synchronized String submit(ServerPlayer player, String questId) {
        if (!hasCatalog()) return "任务系统尚未加载";
        QuestDefinition.Quest quest = catalog.quest(questId);
        if (quest == null) return "任务不存在: " + questId;
        if (!isRotationQuest(player.getUUID(), quest.id) && !catalog.chapterOf(quest.id).isBlank()
                && !unlocked(player.getUUID(), quest.id)) {
            return "该任务尚未解锁";
        }
        if (!isRotationQuest(player.getUUID(), quest.id) && catalog.chapterOf(quest.id).isBlank()) return "该任务不属于当前任务视图";
        if (claimed(player.getUUID(), quest.id)) return "该任务奖励已领取";
        for (int i = 0; i < quest.tasks.size(); i++) {
            QuestDefinition.Task task = quest.tasks.get(i);
            if (!"submit_item".equals(task.type)) continue;
            long current = progress(player.getUUID(), quest.id, i);
            if (current >= task.amount) continue;
            long need = task.amount - current;
            int have = countItems(player, task);
            if (have < need) {
                return "物品不足：需要 " + need + " 个 " + targetLabel(task) + "，背包中有 " + have + " 个";
            }
            removeItems(player, task, need);
            state(player.getUUID()).progress.put(key(quest.id, i), task.amount);
            dirty = true;
            saveIfDirty();
            MessageUtil.sendSuccess(player, MODULE, "已提交 " + need + " 个 " + targetLabel(task) + "（" + quest.label() + "）");
            if (questComplete(player.getUUID(), quest)) {
                MessageUtil.sendInfo(player, MODULE, "任务已完成，可在任务界面领取奖励");
            }
            return null;
        }
        return "该任务没有待提交的物品目标";
    }

    private int countItems(ServerPlayer player, QuestDefinition.Task task) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (matchesItem(task, stack)) total += stack.getCount();
        }
        return total;
    }

    private void removeItems(ServerPlayer player, QuestDefinition.Task task, long amount) {
        long remaining = amount;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!matchesItem(task, stack)) continue;
            int take = (int) Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
    }

    private boolean matchesItem(QuestDefinition.Task task, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String spec = task.target == null ? "" : task.target.trim();
        if ("*".equals(spec)) return true;
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            return tagId != null && stack.is(TagKey.create(Registries.ITEM, tagId));
        }
        return spec.equals(idOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
    }

    private static String targetLabel(QuestDefinition.Task task) {
        String name = task.display == null || task.display.isBlank() ? task.target : task.display;
        return name == null || name.isBlank() ? "*" : name;
    }

    public synchronized String claim(ServerPlayer player, String questId) {
        if (!hasCatalog()) return "任务系统尚未加载";
        QuestDefinition.Quest quest = catalog.quest(questId);
        if (quest == null) return "任务不存在: " + questId;
        UUID uuid = player.getUUID();
        boolean chapter = !catalog.chapterOf(quest.id).isBlank();
        if (chapter) {
            if (!unlocked(uuid, quest.id)) return "前置任务尚未完成，该任务仍锁定";
        } else if (!isRotationQuest(uuid, quest.id)) {
            return "该任务不在你当前的任务轮换中";
        }
        if (claimed(uuid, quest.id)) return "奖励已领取";
        if (!questComplete(uuid, quest)) return incompleteMessage(uuid, quest);
        PlayerState st = state(uuid);
        st.claimed.add(quest.id);
        if (chapter) st.completed.add(quest.id);
        dirty = true;
        grant(player, quest);
        saveIfDirty();
        MessageUtil.sendSuccess(player, MODULE, "已领取奖励：" + quest.label());
        MessageUtil.playDing(player);
        return null;
    }

    private String incompleteMessage(UUID uuid, QuestDefinition.Quest quest) {
        List<String> pending = new ArrayList<>();
        for (int i = 0; i < quest.tasks.size(); i++) {
            if (!taskComplete(uuid, quest, i)) {
                QuestDefinition.Task task = quest.tasks.get(i);
                String mode = "submit_item".equals(task.type) ? "（使用 /quest submit " + quest.id + "）" : "";
                pending.add(describe(task) + " " + progress(uuid, quest.id, i) + "/" + task.amount + mode);
            }
        }
        return "任务尚未完成：" + String.join("；", pending.subList(0, Math.min(3, pending.size())));
    }

    private void grant(ServerPlayer player, QuestDefinition.Quest quest) {
        for (QuestDefinition.Reward reward : quest.rewards) {
            switch (reward.type) {
                case "coin" -> EconomyManager.get().deposit(player.getUUID(), reward.amount, "Quest " + quest.id);
                case "xp" -> player.giveExperiencePoints((int) Math.max(1, Math.min(Integer.MAX_VALUE, reward.amount)));
                case "item" -> giveItem(player, reward);
                default -> HabiTrainSurvive.LOGGER.warn("未知任务奖励类型 {}（任务 {}）", reward.type, quest.id);
            }
        }
    }

    private void giveItem(ServerPlayer player, QuestDefinition.Reward reward) {
        ResourceLocation id = ResourceLocation.tryParse(reward.item == null ? "" : reward.item);
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            HabiTrainSurvive.LOGGER.warn("任务奖励物品不存在: {}", reward.item);
            return;
        }
        int remaining = Math.max(1, reward.count);
        int perStack = Math.max(1, new ItemStack(item).getMaxStackSize());
        while (remaining > 0) {
            int count = Math.min(perStack, remaining);
            ItemStack stack = new ItemStack(item, count);
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            remaining -= count;
        }
    }

    public synchronized void resetPlayer(UUID uuid) {
        PlayerState fresh = new PlayerState();
        storage.players.put(uuid, fresh);
        dirty = true;
        saveIfDirty();
    }

    /** Deterministic list of ids currently offered to a player; used by the GUI and tests. */
    public synchronized Set<String> rotationIds(ServerPlayer player) {
        Set<String> ids = new LinkedHashSet<>();
        for (QuestDefinition.Quest quest : rotationQuests(player, "daily")) ids.add(quest.id);
        for (QuestDefinition.Quest quest : rotationQuests(player, "weekly")) ids.add(quest.id);
        return ids;
    }

    static List<String> allTemplateIds(QuestCatalog catalog, boolean daily) {
        List<String> ids = new ArrayList<>();
        for (QuestDefinition.Quest quest : daily ? catalog.daily() : catalog.weekly()) ids.add(quest.id);
        return ids;
    }

    public void openGui(ServerPlayer player) { QuestGui.open(player); }

    static List<QuestDefinition.Quest> sortedByPosition(List<QuestDefinition.Quest> quests) {
        List<QuestDefinition.Quest> copy = new ArrayList<>(quests);
        copy.sort(Comparator.comparingInt((QuestDefinition.Quest quest) -> quest.y).thenComparingInt(quest -> quest.x));
        return copy;
    }
}
