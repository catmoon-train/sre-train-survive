package io.wifi.habitrainsurvive.help;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure descriptions only. Syntax and access gates always come from the real command tree. */
public final class HelpMetadata {
    public record Category(String id, String name) { }
    public record Rule(String path, String category, String description, String permission) { }
    public record Match(Rule rule, boolean exact) { }
    public static final List<Category> CATEGORIES = List.of(
            new Category("general", "综合与菜单"), new Category("travel", "传送与家"), new Category("world", "世界与主城"),
            new Category("economy", "经济与回收"), new Category("systemshop", "系统商城"), new Category("playershop", "电子店铺"),
            new Category("chestshop", "箱子商店"), new Category("trade", "交易与拍卖"), new Category("claim", "领地与地皮"),
            new Category("town", "城镇"), new Category("rewards", "礼包与兑换"), new Category("lottery", "抽奖"),
            new Category("survival", "任务墓碑称号"), new Category("social", "聊天与社交"), new Category("tools", "随身工具"),
            new Category("admin", "管理与惩戒"));
    private final Map<String, Rule> rules;
    private final Map<String, String> aliases;

    private HelpMetadata(Map<String, Rule> rules, Map<String, String> aliases) {
        this.rules = Map.copyOf(rules); this.aliases = Map.copyOf(aliases);
        for (String alias : aliases.keySet()) canonicalRoot(alias); // Detect malformed alias cycles at load time.
    }
    public static HelpMetadata loadDefaults() throws IOException {
        try (InputStream a = HelpMetadata.class.getResourceAsStream("/defaults/command_help.json");
             InputStream b = HelpMetadata.class.getResourceAsStream("/defaults/command_help_cmi.json")) {
            if (a == null || b == null) throw new IOException("缺少内置指令帮助说明");
            try (Reader first = new InputStreamReader(a, StandardCharsets.UTF_8); Reader second = new InputStreamReader(b, StandardCharsets.UTF_8)) {
                return read(first, second);
            }
        }
    }
    public static HelpMetadata read(Reader... inputs) {
        Map<String, Rule> rules = new LinkedHashMap<>(); Map<String, String> aliases = new LinkedHashMap<>();
        for (Reader input : inputs) {
            JsonObject root = JsonParser.parseReader(input).getAsJsonObject();
            JsonObject names = root.getAsJsonObject("aliases");
            if (names != null) names.entrySet().forEach(entry -> {
                String previous = aliases.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                if (previous != null && !previous.equals(entry.getValue().getAsString())) throw new IllegalArgumentException("重复指令别名: " + entry.getKey());
            });
            JsonArray commands = root.getAsJsonArray("commands");
            if (commands == null) throw new IllegalArgumentException("指令帮助缺少 commands");
            for (JsonElement element : commands) {
                JsonObject object = element.getAsJsonObject();
                Rule rule = new Rule(required(object, "path"), required(object, "category"), required(object, "description"), required(object, "permission"));
                if (CATEGORIES.stream().noneMatch(category -> category.id.equals(rule.category))) throw new IllegalArgumentException("未知帮助分类: " + rule.category);
                if (rules.putIfAbsent(rule.path, rule) != null) throw new IllegalArgumentException("重复帮助条目: " + rule.path);
            }
        }
        return new HelpMetadata(rules, aliases);
    }
    private static String required(JsonObject object, String key) {
        String value = object.get(key).getAsString();
        if (value.isBlank() || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("无效帮助字段: " + key);
        return value;
    }
    public Map<String, Rule> rules() { return rules; }
    public Map<String, String> aliases() { return aliases; }
    public String canonicalRoot(String root) {
        Set<String> visited = new HashSet<>();
        while (aliases.containsKey(root)) {
            if (!visited.add(root)) throw new IllegalArgumentException("指令别名循环: " + root);
            root = aliases.get(root);
        }
        return root;
    }
    public Match match(String literalPath) {
        String[] words = literalPath.split(" ", 2);
        String canonical = canonicalRoot(words[0]) + (words.length == 2 ? " " + words[1] : "");
        String raw = literalPath, normalized = canonical;
        boolean exact = true;
        while (!raw.isEmpty()) {
            Rule rule = rules.get(raw);
            if (rule == null) rule = rules.get(normalized);
            if (rule != null) return new Match(rule, exact);
            int cut = raw.lastIndexOf(' ');
            if (cut < 0) break;
            raw = raw.substring(0, cut); normalized = normalized.substring(0, normalized.lastIndexOf(' ')); exact = false;
        }
        return new Match(new Rule(literalPath, "general", "本模组指令（说明待补充）", "以指令入口与执行时校验为准"), false);
    }
    public static String categoryId(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (Set.of("all", "全部", "所有").contains(lower)) return "all";
        if (Set.of("categories", "分类", "目录").contains(lower)) return "categories";
        for (Category category : CATEGORIES) if (category.id.equals(lower) || category.name.equals(value)) return category.id;
        return switch (lower) {
            case "菜单", "menu" -> "general"; case "传送", "家", "home", "地标" -> "travel";
            case "世界", "主城", "刷新" -> "world"; case "经济", "回收" -> "economy";
            case "商城", "shop", "系统商店" -> "systemshop"; case "商铺", "电子商铺" -> "playershop";
            case "箱店", "箱子商店" -> "chestshop"; case "拍卖", "交易" -> "trade";
            case "领地", "地皮" -> "claim"; case "城镇" -> "town"; case "礼包", "兑换", "礼物" -> "rewards";
            case "抽奖" -> "lottery"; case "任务", "墓碑", "称号" -> "survival";
            case "聊天", "社交" -> "social"; case "工具" -> "tools"; case "管理", "权限", "惩戒" -> "admin";
            default -> null;
        };
    }
    public static String categoryName(String id) {
        if (id.equals("all")) return "全部指令";
        if (id.equals("categories")) return "分类目录";
        return CATEGORIES.stream().filter(category -> category.id.equals(id)).map(Category::name).findFirst().orElse(id);
    }
    public static int categoryOrder(String id) {
        for (int i = 0; i < CATEGORIES.size(); i++) if (CATEGORIES.get(i).id.equals(id)) return i;
        return CATEGORIES.size();
    }
}
