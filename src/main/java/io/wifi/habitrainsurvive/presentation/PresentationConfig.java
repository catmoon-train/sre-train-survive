package io.wifi.habitrainsurvive.presentation;

import java.util.List;

public final class PresentationConfig {
    public boolean announcementsEnabled = true;
    public int announcementIntervalSeconds = 180;
    public List<String> announcements = List.of("§6[公告] §f/menu 打开主菜单，/guide 查看新手指南。", "§6[公告] §f/daily 每日签到；/gift box 领取礼物。", "§6[公告] §f资源世界会重置，请在永久生存世界安家并圈地保护。", "§6[公告] §f/settings 可关闭侧栏、公告和本模块提示音。");
    public boolean joinLeaveEnabled = true;
    public String joinMessage = "§a+ §f{player} §7加入了服务器 ({online}/{max})";
    public String leaveMessage = "§c- §f{player} §7离开了服务器";
    public boolean sidebarEnabled = true;
    public int refreshSeconds = 5;
    public String sidebarTitle = "§6§l哈比生存";
    public List<String> sidebarLines = List.of("§f玩家: §e{player}", "§f余额: §a{balance}", "§f世界: §b{world}", "§f组别: {group}", "§f在线: §a{online}§7/{max}", "§e/menu §7主菜单", "§7/settings 个性设置");
    public boolean tabEnabled = true;
    public String tabHeader = "§6§l哈比生存服\n§f欢迎 {player} §7| {group}";
    public String tabFooter = "§7在线 §a{online}§7/{max} §8| §e/menu §8| §e/guide\n§7请文明游戏，共建友善社区";
    public boolean defaultSidebar = true;
    public boolean defaultAnnouncements = true;
    public boolean defaultSounds = true;
    public String settingsTitle = "个人设置";

    public void validate() {
        if (announcementIntervalSeconds < 10 || announcementIntervalSeconds > 86400) throw new IllegalArgumentException("announcementIntervalSeconds: expected 10..86400");
        if (refreshSeconds < 2 || refreshSeconds > 300) throw new IllegalArgumentException("refreshSeconds: expected 2..300");
        if (announcements == null || announcements.size() > 100) throw new IllegalArgumentException("announcements: expected at most 100 entries");
        for (int i = 0; i < announcements.size(); i++) ConfigFiles.text(announcements.get(i), "announcements[" + i + "]", 2048, false);
        ConfigFiles.text(joinMessage, "joinMessage", 2048, true);
        ConfigFiles.text(leaveMessage, "leaveMessage", 2048, true);
        ConfigFiles.text(sidebarTitle, "sidebarTitle", 128, false);
        if (sidebarLines == null || sidebarLines.isEmpty() || sidebarLines.size() > 15) throw new IllegalArgumentException("sidebarLines: expected 1..15 entries");
        for (int i = 0; i < sidebarLines.size(); i++) {
            String line = sidebarLines.get(i);
            ConfigFiles.text(line, "sidebarLines[" + i + "]", 256, true);
            if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) throw new IllegalArgumentException("sidebarLines[" + i + "]: must be a single line");
        }
        ConfigFiles.text(tabHeader, "tabHeader", 4096, true);
        ConfigFiles.text(tabFooter, "tabFooter", 4096, true);
        ConfigFiles.text(settingsTitle, "settingsTitle", 128, false);
    }
}
