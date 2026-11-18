package io.wifi.habitrainsurvive.onboarding;

import io.wifi.habitrainsurvive.presentation.ConfigFiles;

import java.util.HashSet;
import java.util.List;

public final class OnboardingConfig {
    public boolean enabled = true;
    public boolean welcomeTitle = true;
    public String title = "§6欢迎来到哈比生存服";
    public String subtitle = "§e{player}，输入 /guide 开始你的旅程";
    public String welcomeMessage = "§a欢迎！使用 /menu 浏览功能，/rules 查看规则，/guide 查看指南。";
    public boolean rewardEnabled = true;
    public String rewardKit = "newbie";
    public String rewardMessage = "§a新手奖励已存入礼物箱，使用 /gift box 领取；背包满时可稍后领取。";
    public String guideTitle = "新手指南";
    public String rulesTitle = "服务器规则";
    public List<String> rules = List.of("尊重他人，禁止辱骂、骚扰与作弊。", "禁止偷窃、恶意破坏、卡服与利用漏洞。", "建造前先圈地；资源世界会重置，不要放置永久资产。", "交易前确认物品与价格，遇到问题请联系管理员。");
    public List<Step> steps = List.of(
            new Step("world", "1. 开始生存", List.of("使用 /world 浏览世界，选择永久生存世界安家。", "资源世界用于采集，会周期重置；/hub 返回主城。"), "world"),
            new Step("claim", "2. 保护家园", List.of("使用 /claim 查看圈地帮助，先保护再建造。", "主城与资源世界不适合建立永久领地。"), "claim"),
            new Step("earn", "3. 赚钱与签到", List.of("使用 /daily 领取现有签到奖励，/quests 查看任务。", "使用 /shop 浏览系统商店；签到每天只计算一次。"), "daily"),
            new Step("trade", "4. 安全交易", List.of("使用 /ah 浏览拍卖，/trade 发起面对面交易。", "交易前确认价格，/gift box 领取礼物。"), "ah"),
            new Step("death", "5. 死亡与找回", List.of("死亡后使用 /grave 查看墓碑与找回方式。", "返回与传送仍受冷却、战斗和世界开放状态限制。"), "grave"));

    public static final class Step {
        public String id;
        public String title;
        public List<String> lines;
        public String command = "";

        public Step() { }

        public Step(String id, String title, List<String> lines, String command) {
            this.id = id;
            this.title = title;
            this.lines = lines;
            this.command = command;
        }
    }

    public void validate() {
        ConfigFiles.text(title, "title", 256, false);
        ConfigFiles.text(subtitle, "subtitle", 512, true);
        ConfigFiles.text(welcomeMessage, "welcomeMessage", 2048, true);
        ConfigFiles.text(rewardMessage, "rewardMessage", 2048, true);
        ConfigFiles.text(guideTitle, "guideTitle", 128, false);
        ConfigFiles.text(rulesTitle, "rulesTitle", 128, false);
        if (rewardKit == null || !rewardKit.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("rewardKit: expected an existing lowercase CMI kit name");
        }
        if (rules == null || rules.isEmpty() || rules.size() > 100) throw new IllegalArgumentException("rules: expected 1..100 lines");
        for (int i = 0; i < rules.size(); i++) ConfigFiles.text(rules.get(i), "rules[" + i + "]", 512, false);
        if (steps == null || steps.isEmpty() || steps.size() > 45) throw new IllegalArgumentException("steps: expected 1..45 steps");
        var ids = new HashSet<String>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String field = "steps[" + i + "]";
            if (step == null || step.id == null || !step.id.matches("[a-z0-9_-]{1,64}") || !ids.add(step.id)) {
                throw new IllegalArgumentException(field + ".id: expected a unique stable identifier");
            }
            ConfigFiles.text(step.title, field + ".title", 128, false);
            if (step.lines == null || step.lines.isEmpty() || step.lines.size() > 15) throw new IllegalArgumentException(field + ".lines: expected 1..15 lines");
            for (String line : step.lines) ConfigFiles.text(line, field + ".lines", 256, false);
            ConfigFiles.text(step.command, field + ".command", 256, true);
            if (step.command.startsWith("/") || step.command.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(field + ".command: expected a single player command without leading slash");
            }
        }
    }
}
