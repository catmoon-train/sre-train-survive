package io.wifi.habitrainsurvive.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MenuStoreSaveTest {
    @TempDir Path directory;

    private Path file() { return directory.resolve("menus.json"); }

    private static JsonObject config() {
        return JsonParser.parseString("""
                {"version":1,"defaultMenu":"main","menus":{"main":{"title":"Main","rows":3,
                "buttons":[{"slot":0,"icon":"minecraft:stone","name":"Button","actions":[{"type":"CLOSE"}]}]}}}
                """).getAsJsonObject();
    }

    private MenuConfig.Store loadedStore() throws IOException {
        Files.writeString(file(), "  " + config() + "\n", StandardCharsets.UTF_8);
        MenuConfig.Store store = new MenuConfig.Store();
        store.load(file(), c -> { });
        return store;
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(List.of("menus.json"), files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test void savesValidatesBeforeWritesAndReloadsAllNewFields() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig initial = store.current();
        String before = Files.readString(file());
        MenuDraft draft = new MenuDraft(initial);
        draft.setAutoShortcuts(false);
        draft.setSneakSwap(true);
        draft.button("main", 0).addProperty("amount", 32);
        draft.page("main").addProperty("title", "自定义标题");
        AtomicBoolean validated = new AtomicBoolean();
        store.save(initial, draft.json(), candidate -> {
            assertSame(initial, store.current());
            assertEquals(before, assertDoesNotThrow(() -> Files.readString(file())));
            assertEquals(32, candidate.menus().get("main").buttons().getFirst().amount());
            validated.set(true);
        });
        assertTrue(validated.get());
        assertNotSame(initial, store.current());
        assertFalse(store.current().autoShortcuts());
        assertTrue(store.current().sneakSwapEnabled());
        MenuConfig saved = store.current();
        store.reload(c -> { });
        assertEquals(saved, store.current());
        MenuConfig.Store second = new MenuConfig.Store();
        second.load(file(), c -> { });
        assertEquals(saved, second.current());
        // The save baseline must be the newly written text, not the original formatting.
        store.save(store.current(), MenuConfig.toJson(store.current()), c -> { });
        assertNoTemporaryFiles();
    }

    @Test void rejectsStaleSnapshotAfterSaveWithoutReplacingCurrentOrFile() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig stale = store.current();
        JsonObject candidate = MenuConfig.toJson(stale);
        candidate.addProperty("sneakSwapEnabled", true);
        store.save(stale, candidate, c -> { });
        MenuConfig active = store.current();
        String saved = Files.readString(file());
        assertThrows(IOException.class, () -> store.save(stale, MenuConfig.toJson(stale), c -> fail("Stale save validated")));
        assertSame(active, store.current());
        assertEquals(saved, Files.readString(file()));
        assertNoTemporaryFiles();
    }

    @Test void requiresExpectedIdentityNotJustEqualContents() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig current = store.current();
        MenuConfig equal = MenuConfig.read(new StringReader(MenuConfig.toJson(current).toString()));
        assertEquals(current, equal);
        assertNotSame(current, equal);
        String before = Files.readString(file());
        assertThrows(IOException.class, () -> store.save(equal, MenuConfig.toJson(equal), c -> { }));
        assertSame(current, store.current());
        assertEquals(before, Files.readString(file()));
        assertNoTemporaryFiles();
    }

    @Test void rejectsEvenFormattingOnlyExternalEditsAndKeepsExternalText() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig current = store.current();
        String external = Files.readString(file()) + "  \n";
        Files.writeString(file(), external, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.save(current, MenuConfig.toJson(current), c -> { }));
        assertSame(current, store.current());
        assertEquals(external, Files.readString(file()));
        assertNoTemporaryFiles();
    }

    @Test void rejectsExternalDeletionWithoutRecreatingFile() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig current = store.current();
        Files.delete(file());
        assertThrows(IOException.class, () -> store.save(current, MenuConfig.toJson(current), c -> { }));
        assertSame(current, store.current());
        assertFalse(Files.exists(file()));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void strictInvalidCandidateNeverChangesDiskOrRuntime() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig current = store.current();
        String before = Files.readString(file());
        JsonObject unknown = MenuConfig.toJson(current);
        unknown.addProperty("typo", true);
        JsonObject amount = MenuConfig.toJson(current);
        amount.getAsJsonObject("menus").getAsJsonObject("main").getAsJsonArray("buttons").get(0).getAsJsonObject().addProperty("amount", 65);
        JsonObject sequence = MenuConfig.toJson(current);
        JsonArray actions = sequence.getAsJsonObject("menus").getAsJsonObject("main").getAsJsonArray("buttons").get(0).getAsJsonObject().getAsJsonArray("actions");
        actions.add(JsonParser.parseString("{\"type\":\"MESSAGE\",\"value\":\"after close\"}"));
        JsonObject cycle = MenuConfig.toJson(current);
        cycle.getAsJsonObject("menus").getAsJsonObject("main").addProperty("back", "main");
        for (JsonObject invalid : List.of(unknown, amount, sequence, cycle)) {
            assertThrows(IllegalArgumentException.class, () -> store.save(current, invalid, c -> fail("Invalid candidate reached registries")));
            assertSame(current, store.current());
            assertEquals(before, Files.readString(file()));
            assertNoTemporaryFiles();
        }
    }

    @Test void failedRegistryValidationLeavesDiskAndCurrentUntouched() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig current = store.current();
        String before = Files.readString(file());
        JsonObject candidate = MenuConfig.toJson(current);
        candidate.addProperty("sneakSwapEnabled", true);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> store.save(current, candidate, c -> { throw new IllegalArgumentException("unknown item registry entry"); }));
        assertTrue(failure.getMessage().contains("registry"));
        assertSame(current, store.current());
        assertEquals(before, Files.readString(file()));
        assertNoTemporaryFiles();
    }

    @Test void rejectsOversizedUtf8BeforeValidationOrWrites() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig current = store.current();
        String before = Files.readString(file());
        JsonObject candidate = MenuConfig.toJson(current);
        candidate.getAsJsonObject("menus").getAsJsonObject("main").addProperty("title", "界".repeat(360_000));
        assertTrue(candidate.toString().length() < 1_048_576);
        assertTrue(candidate.toString().getBytes(StandardCharsets.UTF_8).length > 1_048_576);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> store.save(current, candidate, c -> fail("Oversized candidate validated")));
        assertTrue(failure.getMessage().contains("1 MiB"));
        assertSame(current, store.current());
        assertEquals(before, Files.readString(file()));
        assertNoTemporaryFiles();
    }

    @Test void failedReloadRetainsSnapshotAndExactLastSuccessfulDiskBaseline() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig current = store.current();
        String original = Files.readString(file());
        Files.writeString(file(), "{}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> store.reload(c -> { }));
        assertSame(current, store.current());
        assertThrows(IOException.class, () -> store.save(current, MenuConfig.toJson(current), c -> { }));
        assertEquals("{}", Files.readString(file()));
        Files.writeString(file(), original, StandardCharsets.UTF_8);
        store.save(current, MenuConfig.toJson(current), c -> { });
        assertEquals(current, store.current());
        assertNoTemporaryFiles();
    }

    @Test void checksExternalChangesMadeDuringRegistryValidation() throws Exception {
        MenuConfig.Store store = loadedStore();
        MenuConfig current = store.current();
        String external = "\n" + Files.readString(file());
        assertThrows(IOException.class, () -> store.save(current, MenuConfig.toJson(current), c ->
                assertDoesNotThrow(() -> Files.writeString(file(), external, StandardCharsets.UTF_8))));
        assertSame(current, store.current());
        assertEquals(external, Files.readString(file()));
        assertNoTemporaryFiles();
    }

    @Test void unloadedStoreCannotSaveOrCreateFiles() throws Exception {
        MenuConfig.Store store = new MenuConfig.Store();
        assertThrows(IOException.class, () -> store.save(null, config(), c -> { }));
        assertNull(store.current());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
