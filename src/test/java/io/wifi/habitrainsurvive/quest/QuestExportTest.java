package io.wifi.habitrainsurvive.quest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QuestExportTest {
    @Test void bundleJsonIsInjectedAtTheExactMarker() {
        String template = "<script>window.QUEST_BUNDLE = /*__QUEST_BUNDLE__*/null;</script>";
        String injected = QuestExport.inject(template, "{\"version\":1}");
        assertTrue(injected.contains("window.QUEST_BUNDLE = {\"version\":1};"));
        assertFalse(injected.contains(QuestExport.MARKER));
    }

    @Test void closingScriptInContentCannotBreakOutOfTheScriptElement() {
        String template = "<script>window.QUEST_BUNDLE = /*__QUEST_BUNDLE__*/null;</script>";
        String injected = QuestExport.inject(template, "{\"t\":\"</script><script>alert(1)</script>\"}");
        assertFalse(injected.contains("</script><script>alert(1)"));
        assertTrue(injected.contains("<\\/script>"));
    }

    @Test void missingMarkerIsAClearFailure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> QuestExport.inject("<html></html>", "{}"));
        assertTrue(error.getMessage().contains("注入标记"));
    }

    @Test void injectedTemplateKeepsItsSingleDocumentShape() {
        String template = "<!doctype html><html><body><script>window.QUEST_BUNDLE = /*__QUEST_BUNDLE__*/null;</script></body></html>";
        String injected = QuestExport.inject(template, "{}");
        assertTrue(injected.startsWith("<!doctype html>"));
        assertTrue(injected.endsWith("</html>"));
    }

    @Test void triggerMetadataIsStableAndIncludesSubmission() {
        List<Map<String, String>> metadata = QuestTrigger.metadata();
        assertTrue(metadata.stream().anyMatch(entry -> "submit_item".equals(entry.get("type"))));
        assertTrue(metadata.stream().anyMatch(entry -> "kill_entity".equals(entry.get("type"))));
        assertTrue(metadata.stream().anyMatch(entry -> "online_time".equals(entry.get("type"))));
        assertEquals("ITEM", metadata.stream().filter(entry -> "obtain_item".equals(entry.get("type")))
                .findFirst().orElseThrow().get("targetKind"));
    }
    @Test void shippedTemplateIsOfflineAndCarriesTheMarker() throws Exception {
        String template;
        try (InputStream stream = QuestExportTest.class.getResourceAsStream(QuestExport.TEMPLATE_RESOURCE)) {
            assertNotNull(stream, "编辑器模板未打包进模组");
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(1, count(template, QuestExport.MARKER));
        assertFalse(template.toLowerCase(java.util.Locale.ROOT).contains("http://"));
        assertFalse(template.contains("https://cdn"));
        assertTrue(template.contains("<!DOCTYPE html>") || template.contains("<!doctype html>"));
        String injected = QuestExport.inject(template, "{\"version\":1}");
        assertFalse(injected.contains(QuestExport.MARKER));
    }

    private static int count(String text, String needle) {
        int hits = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) hits++;
        return hits;
    }
}
