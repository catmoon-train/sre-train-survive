package io.wifi.habitrainsurvive.onboarding;

import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.menu.MenuManager;
import io.wifi.habitrainsurvive.presentation.PresentationManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GuideGui {
    private GuideGui() { }

    public static void open(ServerPlayer player) {
        var manager = OnboardingManager.get();
        var config = manager.config();
        List<MenuGui.Button> buttons = new ArrayList<>();
        for (int i = 0; i < config.steps.size(); i++) {
            int index = i;
            var step = config.steps.get(i);
            List<String> lore = new ArrayList<>(step.lines);
            lore.add(manager.hasRead(player.getUUID(), step.id) ? "§a已阅读（可重复查看）" : "§e点击查看，按步骤学习");
            buttons.add(new MenuGui.Button(MenuGui.icon(Items.BOOK, step.title, lore.toArray(String[]::new)), p -> openStep(p, index)));
        }
        buttons.add(new MenuGui.Button(MenuGui.icon(Items.WRITABLE_BOOK, config.rulesTitle, "§7查看完整服务器规则"), p -> openRules(p, 0)));
        buttons.add(new MenuGui.Button(MenuGui.icon(Items.CHEST, "新手礼物箱", "§7首次奖励保存在现有礼物箱", "§7无需腾空整个背包，可逐份领取"), p -> command(p, "gift box")));
        buttons.add(new MenuGui.Button(MenuGui.icon(Items.CLOCK, "每日签到", "§7使用现有 /daily，不重复计算奖励"), p -> command(p, "daily")));
        MenuGui.open(player, config.guideTitle, buttons, 0, p -> MenuManager.get().open(p, "main"));
    }

    public static void openStep(ServerPlayer player, int index) {
        var manager = OnboardingManager.get();
        var config = manager.config();
        int current = Math.clamp(index, 0, config.steps.size() - 1);
        var step = config.steps.get(current);
        Map<Integer, MenuGui.Button> slots = new HashMap<>();
        slots.put(13, new MenuGui.Button(MenuGui.icon(Items.BOOK, step.title, step.lines.toArray(String[]::new)), p -> { }));
        slots.put(21, new MenuGui.Button(MenuGui.icon(Items.ARROW, "返回指南目录"), GuideGui::open));
        slots.put(22, new MenuGui.Button(MenuGui.icon(Items.LIME_DYE, "标记已读并继续", "§7这是阅读进度，不伪装成游戏任务完成"), p -> {
            manager.markRead(p, step.id);
            if (current + 1 < manager.config().steps.size()) openStep(p, current + 1); else open(p);
        }));
        if (!step.command.isBlank()) slots.put(15, new MenuGui.Button(MenuGui.icon(Items.COMPASS, "打开相关功能", "§e/" + step.command, "§7以你自己的权限执行"), p -> command(p, step.command)));
        if (current > 0) slots.put(18, new MenuGui.Button(MenuGui.icon(Items.ARROW, "上一步"), p -> openStep(p, current - 1)));
        if (current + 1 < config.steps.size()) slots.put(26, new MenuGui.Button(MenuGui.icon(Items.ARROW, "下一步"), p -> openStep(p, current + 1)));
        slots.put(24, new MenuGui.Button(MenuGui.icon(Items.NETHER_STAR, "返回主菜单"), p -> MenuManager.get().open(p, "main")));
        MenuGui.openSlots(player, config.guideTitle + " " + (current + 1) + "/" + config.steps.size(), 3, slots);
    }

    public static void openRules(ServerPlayer player, int page) {
        var config = OnboardingManager.get().config();
        List<MenuGui.Button> rules = new ArrayList<>();
        for (int i = 0; i < config.rules.size(); i++) {
            rules.add(new MenuGui.Button(MenuGui.icon(Items.PAPER, "规则 " + (i + 1), config.rules.get(i)), p -> { }));
        }
        MenuGui.open(player, config.rulesTitle, rules, page, GuideGui::open);
    }

    private static void command(ServerPlayer player, String command) {
        if (!PresentationManager.isConnected(player)) return;
        player.closeContainer();
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }
}
