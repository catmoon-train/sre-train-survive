package io.wifi.habitrainsurvive.quest;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Chest UI only; every action goes through QuestManager so the GUI cannot mutate state directly. */
public final class QuestGui {
    private QuestGui() { }

    /** Admin bridge: chapter authoring happens in the browser, started from the in-game chapter list. */
    private static void openWebEditor(ServerPlayer player) {
        try {
            Path directory = FabricLoader.getInstance().getConfigDir().resolve(HabiTrainSurvive.MOD_ID);
            String url = QuestWebEditor.start(player.server, directory, null, null);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "章节/任务在线编辑已启动：" + url
                            + "\n在浏览器打开即可编辑每日、每周与章节任务；保存会自动校验并写回 quests.json。"));
            MessageUtil.sendInfo(player, "任务", "关闭编辑器：/quest web stop");
        } catch (Exception exception) {
            MessageUtil.sendError(player, "任务", "启动在线编辑失败: " + exception.getMessage());
        }
    }

    public static void open(ServerPlayer player) { openDaily(player); }

    private static void openDaily(ServerPlayer player) { openRotation(player, "daily"); }

    private static void openWeekly(ServerPlayer player) { openRotation(player, "weekly"); }

    private static void openChapters(ServerPlayer player) {
        QuestManager manager = QuestManager.get();
        if (!manager.hasCatalog()) { MessageUtil.sendError(player, "任务", "任务系统尚未加载: " + manager.catalogError()); return; }
        List<MenuGui.Button> buttons = new ArrayList<>(tabs(player, "chapters"));
        for (QuestDefinition.Chapter chapter : manager.catalog().chapters()) {
            int total = chapter.quests.size();
            int done = 0;
            for (QuestDefinition.Quest quest : chapter.quests) if (manager.completedChapterQuest(player.getUUID(), quest.id)) done++;
            List<String> lore = new ArrayList<>();
            if (chapter.description != null && !chapter.description.isBlank()) lore.add("§7" + chapter.description);
            lore.add("§e章节进度: " + done + " / " + total);
            lore.add("§7章节任务为终身进度，解锁后永久保留");
            lore.add("§a点击查看任务");
            buttons.add(new MenuGui.Button(icon(chapter.icon, "§6" + chapter.label(), lore), p -> openChapter(p, chapter.id)));
        }
        if (PermissionManager.get().hasPermission(player, "habi.admin")) {
            buttons.add(new MenuGui.Button(icon("minecraft:writable_book", "§a编辑章节与任务",
                    List.of("§7打开临时网页编辑器", "§7可新建章节、章节内任务、坐标与前置依赖",
                            "§7保存后立即生效，无需重启")), QuestGui::openWebEditor));
        }
        MenuGui.open(player, "📚 章节任务", buttons, 0, null);
    }

    private static void openChapter(ServerPlayer player, String chapterId) {
        QuestManager manager = QuestManager.get();
        QuestDefinition.Chapter chapter = manager.catalog() == null ? null : manager.catalog().chapter(chapterId);
        if (chapter == null) { MessageUtil.sendError(player, "任务", "章节不存在"); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (QuestDefinition.Quest quest : QuestManager.sortedByPosition(chapter.quests)) {
            boolean unlocked = manager.unlocked(player.getUUID(), quest.id);
            if (quest.hidden && !unlocked) continue;
            buttons.add(new MenuGui.Button(questIcon(player, quest, unlocked), p -> {
                if (!manager.unlocked(p.getUUID(), quest.id)) {
                    MessageUtil.sendError(p, "任务", "前置任务尚未完成: " + requiresText(quest));
                    return;
                }
                claim(p, quest, () -> openChapter(p, chapter.id));
            }));
        }
        MenuGui.open(player, "📚 " + chapter.label(), buttons, 0, QuestGui::openChapters);
    }

    private static void openRotation(ServerPlayer player, String kind) {
        QuestManager manager = QuestManager.get();
        if (!manager.hasCatalog()) { MessageUtil.sendError(player, "任务", "任务系统尚未加载: " + manager.catalogError()); return; }
        boolean daily = "daily".equals(kind);
        List<MenuGui.Button> buttons = new ArrayList<>(tabs(player, kind));
        for (QuestDefinition.Quest quest : manager.rotationQuests(player, kind)) {
            buttons.add(new MenuGui.Button(questIcon(player, quest, true), p -> claim(p, quest, () -> openRotation(p, kind))));
        }
        MenuGui.open(player, daily ? "📜 每日任务" : "📅 每周任务", buttons, 0, null);
    }

    private static List<MenuGui.Button> tabs(ServerPlayer player, String current) {
        QuestManager manager = QuestManager.get();
        List<MenuGui.Button> tabs = new ArrayList<>();
        tabs.add(new MenuGui.Button(icon("minecraft:clock", "§e每日任务",
                List.of("§7每天北京时间 0 点刷新", "§7当前周目: " + manager.dailyPeriod(),
                        current.equals("daily") ? "§a当前页面" : "§a点击切换")), p -> {
            if (!current.equals("daily")) openDaily(p);
        }));
        tabs.add(new MenuGui.Button(icon("minecraft:written_book", "§e每周任务",
                List.of("§7每周一北京时间 0 点刷新", "§7当前周目: " + manager.weeklyPeriod(),
                        current.equals("weekly") ? "§a当前页面" : "§a点击切换")), p -> {
            if (!current.equals("weekly")) openWeekly(p);
        }));
        tabs.add(new MenuGui.Button(icon("minecraft:bookshelf", "§e章节任务",
                List.of("§7类似 FTB 的任务树", "§7完成前置后永久解锁",
                        current.equals("chapters") ? "§a当前页面" : "§a点击切换")), p -> {
            if (!current.equals("chapters")) openChapters(p);
        }));
        return tabs;
    }

    private static ItemStack questIcon(ServerPlayer player, QuestDefinition.Quest quest, boolean unlocked) {
        QuestManager manager = QuestManager.get();
        boolean complete = manager.questComplete(player.getUUID(), quest);
        boolean claimed = manager.claimed(player.getUUID(), quest.id);
        List<String> lore = new ArrayList<>();
        if (quest.description != null && !quest.description.isBlank()) lore.add("§7" + quest.description);
        for (int i = 0; i < quest.tasks.size(); i++) {
            QuestDefinition.Task task = quest.tasks.get(i);
            long progress = manager.progress(player.getUUID(), quest.id, i);
            String mark = progress >= task.amount ? "§a✔ " : "§8• ";
            lore.add(mark + "§f" + QuestManager.describe(task) + " §7(" + progress + "/" + task.amount + ")");
        }
        lore.add("§6奖励: " + rewardText(quest));
        if (!unlocked) lore.add("§c前置未完成: " + requiresText(quest));
        else if (claimed) lore.add("§7已领取");
        else if (complete) lore.add("§a点击领取奖励");
        else lore.add("§e进行中");
        String name = (claimed ? "§7" : complete ? "§a" : unlocked ? "§f" : "§8") + quest.label();
        if (!unlocked) return icon("minecraft:barrier", name, lore);
        return icon(quest.icon, name, lore);
    }

    private static String requiresText(QuestDefinition.Quest quest) {
        if (quest.requires == null || quest.requires.isEmpty()) return "无";
        QuestManager manager = QuestManager.get();
        List<String> names = new ArrayList<>();
        for (String id : quest.requires) {
            QuestDefinition.Quest required = manager.catalog().quest(id);
            names.add(required == null ? id : required.label());
        }
        return String.join("、", names);
    }

    static String rewardText(QuestDefinition.Quest quest) {
        List<String> parts = new ArrayList<>();
        for (QuestDefinition.Reward reward : quest.rewards) {
            switch (reward.type) {
                case "coin" -> parts.add(EconomyManager.get().format(reward.amount));
                case "xp" -> parts.add((int) reward.amount + " 经验");
                case "item" -> parts.add(reward.count + "x " + reward.item);
                default -> parts.add(reward.type);
            }
        }
        return String.join(" + ", parts);
    }

    private static void claim(ServerPlayer player, QuestDefinition.Quest quest, Runnable reopen) {
        String error = QuestManager.get().claim(player, quest.id);
        if (error != null) {
            MessageUtil.sendError(player, "任务", error);
            return;
        }
        player.closeContainer();
        reopen.run();
    }

    static ItemStack icon(String itemId, String name, List<String> lore) {
        Item item = Items.PAPER;
        ResourceLocation id = ResourceLocation.tryParse(itemId == null ? "" : itemId);
        if (id != null) {
            Item resolved = BuiltInRegistries.ITEM.get(id);
            if (resolved != null && resolved != Items.AIR) item = resolved;
        }
        return MenuGui.icon(item, name, lore.toArray(String[]::new));
    }
}
