package io.wifi.habitrainsurvive.quest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 在线编辑：页面注入、配置回退与端口默认值，不启动真实监听。 */
class QuestWebEditorTest {
    @Test void onlineInjectionAddsTokenBeforeTheBundleScript() {
        String html = "<!doctype html><html><head><script>window.QUEST_BUNDLE = null;</script></head></html>";
        String injected = QuestExport.online(html, "abc123");
        int online = injected.indexOf("window.QUEST_ONLINE");
        int bundle = injected.indexOf("window.QUEST_BUNDLE");
        assertTrue(online >= 0, "缺少在线模式标记");
        assertTrue(online < bundle, "在线模式必须在数据脚本之前定义");
        assertTrue(injected.contains("\"token\":\"abc123\""));
        assertTrue(injected.contains("/api/quests"));
        assertTrue(injected.startsWith("<!doctype html>"));
        assertTrue(injected.endsWith("</html>"));
    }

    @Test void onlineInjectionRefusesAPageWithoutTheDataSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> QuestExport.online("<html></html>", "token"));
        assertTrue(error.getMessage().contains("注入位置"));
    }

    @Test void defaultPortIs11088AndMissingConfigFallsBackWithoutWriting(@TempDir Path directory) {
        assertEquals(11088, QuestWebEditor.DEFAULT_PORT);
        QuestWebEditor.Config config = QuestWebEditor.readConfig(directory);
        assertEquals(11088, config.port);
        assertEquals("0.0.0.0", config.bindAddress);
        assertFalse(Files.exists(directory.resolve(QuestWebEditor.CONFIG_FILE)), "读取缺失配置不应写盘");
    }

    @Test void invalidConfigValuesFallBackToTheDefaultPort(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve(QuestWebEditor.CONFIG_FILE),
                "{\"bindAddress\":\"127.0.0.1\",\"port\":70000}", StandardCharsets.UTF_8);
        QuestWebEditor.Config config = QuestWebEditor.readConfig(directory);
        assertEquals(QuestWebEditor.DEFAULT_PORT, config.port);
        assertEquals("127.0.0.1", config.bindAddress);

        Files.writeString(directory.resolve(QuestWebEditor.CONFIG_FILE), "{broken", StandardCharsets.UTF_8);
        assertEquals(QuestWebEditor.DEFAULT_PORT, QuestWebEditor.readConfig(directory).port);
    }

    @Test void configuredPortIsReadBack(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve(QuestWebEditor.CONFIG_FILE),
                "{\"bindAddress\":\"127.0.0.1\",\"port\":11090}", StandardCharsets.UTF_8);
        assertEquals(11090, QuestWebEditor.readConfig(directory).port);
    }

    @Test void invalidDefinitionsAreRejectedBeforeAnythingIsWritten() {
        // 非法 JSON 必须先失败，且不能把任务文件写坏。
        String broken = "{\"daily\":[{\"id\":\"\",\"title\":\"\",\"tasks\":[],\"rewards\":[]}]}";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> QuestCatalog.parse(new com.google.gson.Gson(), broken));
        assertFalse(error.getMessage().isBlank());
    }

    @Test void editorPageCarriesTheOnlineSaveButtonButStaysHiddenByDefault() throws Exception {
        String template = QuestExport.template();
        assertTrue(template.contains("id=\"btnOnlineSave\""), "缺少在线保存按钮");
        assertTrue(template.contains("data-action=\"online-save\""));
        assertTrue(template.contains("btnOnlineSave\" data-action=\"online-save\" title=\"校验并直接保存回服务器的 quests.json\" hidden")
                        || template.contains("hidden><span data-icon=\"check\">"),
                "离线打开时按钮必须默认隐藏");
        assertTrue(template.contains("window.QUEST_ONLINE"), "页面需要读取在线模式标记");
        assertTrue(template.contains("function saveToServer"));
        assertTrue(template.contains("X-Quest-Token"));
        // 仍然保持离线单文件：不允许引入外部脚本或样式。
        assertFalse(template.toLowerCase(java.util.Locale.ROOT).contains("http://"));
    }

    @Test void onlineTemplateStillInjectsTheBundleAtTheExactMarker() throws Exception {
        // 只走模板注入路径：真正的 registry 枚举需要 Minecraft Bootstrap，不在单元测试内启动。
        String html = QuestExport.inject(QuestExport.template(), "{}");
        assertFalse(html.contains(QuestExport.MARKER));
        String online = QuestExport.online(html, "t");
        // 模板自身会在运行时读取 window.QUEST_ONLINE，因此只统计注入的赋值语句。
        assertEquals(1, count(online, "<script>window.QUEST_ONLINE = "));
        assertFalse(online.contains(QuestExport.MARKER));
        // 模板里另有一处运行时读取，这里只校验数据脚本本身。
        assertEquals(1, count(online, "<script>window.QUEST_BUNDLE"));
        assertTrue(online.indexOf("window.QUEST_ONLINE") < online.indexOf("window.QUEST_BUNDLE"));
    }

    private static int count(String text, String needle) {
        int hits = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) hits++;
        return hits;
    }
}
