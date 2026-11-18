package io.wifi.habitrainsurvive.quest;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds one self-contained offline editor file. The template holds no data; the bundle is
 * injected at an exact marker so the same code path is testable without Minecraft registries.
 */
public final class QuestExport {
    public static final String TEMPLATE_RESOURCE = "/habi_train_survive/quest_editor_template.html";
    public static final String MARKER = "/*__QUEST_BUNDLE__*/null";
    /** 单行 JSON：内联脚本里的标记不能跨行。 */
    private static final com.google.gson.Gson COMPACT = new com.google.gson.Gson();

    private QuestExport() { }

    public static Path writeEditor(MinecraftServer server, Path directory) throws IOException {
        Path file = directory.resolve("quest_editor.html");
        Files.createDirectories(directory);
        Files.writeString(file, html(server), StandardCharsets.UTF_8);
        return file;
    }

    public static String html(MinecraftServer server) throws IOException {
        String template = template();
        String json = ServerDataManager.GSON.toJson(bundle(server));
        return inject(template, json);
    }

    public static String template() throws IOException {
        try (InputStream stream = QuestExport.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (stream == null) throw new IOException("缺少编辑器模板 " + TEMPLATE_RESOURCE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Escaped so a quest description can never terminate the surrounding script element. */
    public static String inject(String template, String bundleJson) {
        if (template == null || !template.contains(MARKER)) {
            throw new IllegalArgumentException("编辑器模板缺少注入标记 " + MARKER);
        }
        String safe = bundleJson.replace("</", "<\\/");
        return template.replace(MARKER, safe);
    }

    /**
     * 在线模式：把令牌注入页面，编辑器据此调用 /api/quests 直接写回服务器。
     * 令牌只出现在已通过鉴权的响应里，且随每次启动更换。
     */
    public static String online(String html, String token) {
        // 紧凑 JSON：标记必须在一行内，避免换行影响页面脚本的顺序判断。
        String script = "<script>window.QUEST_ONLINE = "
                + COMPACT.toJson(Map.of("token", token, "endpoint", "/api/quests"))
                + ";</script>";
        int head = html.indexOf("<script>window.QUEST_BUNDLE");
        if (head < 0) throw new IllegalArgumentException("编辑器页面缺少数据注入位置");
        return html.substring(0, head) + script + "\n" + html.substring(head);
    }

    public static Map<String, Object> bundle(MinecraftServer server) {
        QuestManager manager = QuestManager.get();
        QuestCatalog catalog = manager.catalog();
        Map<String, Object> quests = new LinkedHashMap<>();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("dailyCount", catalog == null ? 0 : catalog.settings().dailyCount);
        settings.put("weeklyCount", catalog == null ? 0 : catalog.settings().weeklyCount);
        settings.put("timeZone", catalog == null ? "Asia/Shanghai" : catalog.settings().timeZone);
        quests.put("settings", settings);
        quests.put("daily", catalog == null ? List.of() : catalog.daily());
        quests.put("weekly", catalog == null ? List.of() : catalog.weekly());
        quests.put("chapters", catalog == null ? List.of() : catalog.chapters());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("version", 1);
        bundle.put("generatedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        bundle.put("quests", quests);
        bundle.put("registry", registry());
        bundle.put("triggers", QuestTrigger.metadata());
        bundle.put("rewardTypes", List.of(
                Map.of("type", "coin", "label", "金币"),
                Map.of("type", "item", "label", "物品"),
                Map.of("type", "xp", "label", "经验")));
        return bundle;
    }

    public static Map<String, Object> registry() {
        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("items", ids(BuiltInRegistries.ITEM.keySet(), "minecraft:air"));
        registry.put("blocks", ids(BuiltInRegistries.BLOCK.keySet(), "minecraft:air"));
        List<String> entities = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) entities.add(id.toString());
        entities.sort(String::compareTo);
        registry.put("entities", entities);
        return registry;
    }

    private static List<String> ids(Iterable<ResourceLocation> keys, String exclude) {
        TreeSet<String> set = new TreeSet<>();
        for (ResourceLocation key : keys) {
            String id = key.toString();
            if (!id.equals(exclude)) set.add(id);
        }
        return new ArrayList<>(set);
    }
}
