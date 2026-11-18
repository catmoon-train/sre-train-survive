package io.wifi.habitrainsurvive.economy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VirtualProxyEconomyTest {
    @TempDir Path directory;
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String SECRET = "test-secret-for-economy-32-characters";

    private EconomyManager manager() {
        EconomyManager manager = new EconomyManager();
        manager.load(directory.resolve("economy.json"));
        manager.getOrCreateAccount(ALICE, "Alice");
        return manager;
    }

    @Test void committedReceiptSurvivesRestartAndOtherBalanceChanges() throws Exception {
        EconomyManager manager = manager();
        ExchangeReceipt first = manager.exchangeAdjust(ALICE, "Alice", "tx-1-debit", -500);
        assertEquals(99500, first.balanceCents());
        manager.deposit(ALICE, 20, "gameplay");
        manager.save(directory.resolve("economy.json"));
        EconomyManager restored = manager();
        assertEquals(first, restored.exchangeAdjust(ALICE, "Alice", "tx-1-debit", -500));
        assertEquals(1015, restored.getBalance(ALICE));
        assertThrows(ExchangeReceipt.ExchangeConflict.class,
                () -> restored.exchangeAdjust(ALICE, "Alice", "tx-1-debit", -1000));
        restored.getOrCreateAccount(BOB, "Bob");
        assertThrows(ExchangeReceipt.ExchangeConflict.class,
                () -> restored.exchangeAdjust(BOB, "Bob", "tx-1-debit", -500));
    }

    @Test void insufficientFundsDoesNotCreateReceiptAndUnknownNamesNeverCreateAccounts() throws Exception {
        EconomyManager manager = manager();
        assertThrows(ExchangeReceipt.ExchangeConflict.class, () -> manager.exchangeAdjust(ALICE, "Alice", "tx", -100001));
        assertEquals(1000, manager.getBalance(ALICE));
        assertFalse(Files.exists(directory.resolve("economy.json")));
        assertThrows(java.util.NoSuchElementException.class, () -> manager.exchangeAccount(BOB, "Missing"));
        assertEquals(ALICE, manager.exchangeAccount(BOB, "alice").uuid);
        assertEquals(100001, manager.exchangeAdjust(ALICE, "Alice", "tx", 1).balanceCents());
    }

    @Test void failedPersistenceLeavesBalanceAndReceiptUntouched() throws Exception {
        EconomyManager manager = manager();
        Files.createDirectory(directory.resolve("economy.json.exchange.tmp"));
        Files.writeString(directory.resolve("economy.json.exchange.tmp/block"), "block");
        assertThrows(IOException.class, () -> manager.exchangeAdjust(ALICE, "Alice", "tx", -500));
        assertEquals(1000, manager.getBalance(ALICE));
        Files.delete(directory.resolve("economy.json.exchange.tmp/block"));
        Files.delete(directory.resolve("economy.json.exchange.tmp"));
        assertEquals(99500, manager.exchangeAdjust(ALICE, "Alice", "tx", -500).balanceCents());
    }

    @Test void corruptSnapshotIsNotReplacedWithEmptyBalances() throws Exception {
        Path path = directory.resolve("economy.json");
        Files.writeString(path, "{broken");
        assertThrows(IllegalStateException.class, () -> new EconomyManager().load(path));
        assertEquals("{broken", Files.readString(path));
    }

    @Test void exactCentsAndBounds() {
        assertEquals(101, ExchangeReceipt.cents(1.005));
        assertThrows(IllegalArgumentException.class, () -> ExchangeReceipt.cents(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ExchangeReceipt.validate("bad id", 1));
        assertThrows(IllegalArgumentException.class, () -> ExchangeReceipt.validate("id", 0));
        assertThrows(IllegalArgumentException.class, () -> ExchangeReceipt.validate("id", Long.MIN_VALUE));
        assertThrows(ExchangeReceipt.ExchangeConflict.class,
                () -> ExchangeReceipt.create(ALICE, 1e12, 1));
    }

    @Test void disabledAndWeakSecretsFailClosed() {
        VirtualProxyEconomyConfig config = new VirtualProxyEconomyConfig();
        assertDoesNotThrow(config::validate);
        config.enabled = true;
        assertThrows(IllegalArgumentException.class, config::validate);
        config.secret = SECRET;
        assertDoesNotThrow(config::validate);
        config.port = 0;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test void httpAuthValidationIdempotencyAndBalance() throws Exception {
        EconomyManager manager = manager();
        VirtualProxyEconomyConfig config = new VirtualProxyEconomyConfig();
        config.enabled = true;
        config.secret = SECRET;
        try (ServerSocket socket = new ServerSocket(0)) { config.port = socket.getLocalPort(); }
        try (VirtualProxyEconomyApi api = new VirtualProxyEconomyApi(manager, Runnable::run, config);
             HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(401, post(client, api, "/balance", "{}", "wrong").statusCode());
            assertEquals(400, post(client, api, "/adjust", "{\"playerName\":\"Alice\",\"requestId\":\"x\",\"deltaCents\":1.5}", SECRET).statusCode());
            assertEquals(400, post(client, api, "/balance", "[]", SECRET).statusCode());
            assertEquals(404, post(client, api, "/balance", "{\"playerName\":\"Missing\"}", SECRET).statusCode());
            String request = "{\"playerUuid\":\"" + ALICE + "\",\"playerName\":\"Alice\",\"requestId\":\"http-1\",\"deltaCents\":-500}";
            HttpResponse<String> response = post(client, api, "/adjust", request, SECRET);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(99500, JsonParser.parseString(response.body()).getAsJsonObject().get("balanceCents").getAsLong());
            assertEquals(response.body(), post(client, api, "/adjust", request, SECRET).body());
            assertEquals(409, post(client, api, "/adjust", request.replace(":-500", ":-501"), SECRET).statusCode());
            HttpResponse<String> insufficient = post(client, api, "/adjust", request.replace("http-1", "http-2").replace(":-500", ":-100000"), SECRET);
            assertEquals(409, insufficient.statusCode());
            assertTrue(JsonParser.parseString(insufficient.body()).getAsJsonObject().get("error").getAsString().contains("insufficient"));
            assertEquals(413, post(client, api, "/balance", " ".repeat(8193), SECRET).statusCode());
            assertEquals(995, manager.getBalance(ALICE));
        }
    }

    private HttpResponse<String> post(HttpClient client, VirtualProxyEconomyApi api, String path, String json, String secret) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .header("X-Economy-Secret", secret).POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
