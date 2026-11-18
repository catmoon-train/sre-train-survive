package io.wifi.habitrainsurvive.economy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.*;

/** Private server-to-server API. All account access runs on the Minecraft server thread. */
public final class VirtualProxyEconomyApi implements AutoCloseable {
    private static VirtualProxyEconomyApi active;
    private final EconomyManager economy;
    private final Executor gameThread;
    private final byte[] secret;
    private final HttpServer http;
    private final ExecutorService workers;
    private volatile boolean accepting = true;

    public static void start(MinecraftServer server, Path directory) {
        stop();
        Path file = directory.resolve("virtualproxy_economy.json");
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, ServerDataManager.GSON.toJson(new VirtualProxyEconomyConfig()), StandardCharsets.UTF_8);
            }
            VirtualProxyEconomyConfig config = ServerDataManager.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), VirtualProxyEconomyConfig.class);
            if (config == null) throw new IllegalArgumentException("金币接口配置不能为空");
            config.validate();
            if (config.enabled) {
                active = new VirtualProxyEconomyApi(EconomyManager.get(), server, config);
                HabiTrainSurvive.LOGGER.info("VirtualProxy 生存金币接口已启用，监听 {}:{}", config.bindAddress, config.port);
            }
        } catch (Exception e) {
            // Keep gameplay available but never fall back to an unauthenticated or default-enabled endpoint.
            HabiTrainSurvive.LOGGER.error("VirtualProxy 金币接口未启动，请检查 virtualproxy_economy.json（{}）", e.getClass().getSimpleName());
        }
    }

    public static void stop() {
        if (active != null) { active.close(); active = null; }
    }

    VirtualProxyEconomyApi(EconomyManager economy, Executor gameThread, VirtualProxyEconomyConfig config) throws IOException {
        config.validate();
        if (!config.enabled) throw new IllegalArgumentException("金币接口尚未启用");
        this.economy = economy;
        this.gameThread = gameThread;
        this.secret = config.secret.getBytes(StandardCharsets.UTF_8);
        http = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 16);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "habi-vproxy-economy"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        http.setExecutor(workers);
        http.createContext("/", this::handle);
        http.start();
    }

    int port() { return http.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String supplied = exchange.getRequestHeaders().getFirst("X-Economy-Secret");
            if (supplied == null || !MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.UTF_8))) {
                send(exchange, 401, Map.of("success", false, "error", "金币接口密钥无效")); return;
            }
            if (!accepting) { send(exchange, 503, Map.of("success", false, "error", "金币接口正在关闭")); return; }
            String path = exchange.getRequestURI().getPath();
            if ("/health".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, Map.of("success", true)); return;
            }
            if (!"/balance".equals(path) && !"/adjust".equals(path)) {
                send(exchange, 404, Map.of("success", false, "error", "接口不存在")); return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, Map.of("success", false, "error", "请使用 POST")); return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(8193);
            if (bytes.length > 8192) { send(exchange, 413, Map.of("success", false, "error", "请求体过大")); return; }
            JsonObject request;
            try { request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IllegalArgumentException("请求体必须为 JSON 对象"); }
            String uuidText = text(request, "playerUuid");
            UUID uuid = uuidText.isBlank() ? null : UUID.fromString(uuidText);
            String name = text(request, "playerName");
            String requestId = text(request, "requestId");
            long delta = "/adjust".equals(path) ? exactLong(request, "deltaCents") : 0;
            if ("/adjust".equals(path)) ExchangeReceipt.validate(requestId, delta);
            CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
            gameThread.execute(() -> {
                try {
                    if (!accepting) throw new IOException("金币接口正在关闭");
                    if ("/balance".equals(path)) {
                        EconomyAccount account = economy.exchangeAccount(uuid, name);
                        result.complete(Map.of("success", true, "playerUuid", account.uuid.toString(), "balanceCents", ExchangeReceipt.cents(account.balance)));
                    } else {
                        ExchangeReceipt receipt = economy.exchangeAdjust(uuid, name, requestId, delta);
                        result.complete(Map.of("success", true, "requestId", requestId, "playerUuid", receipt.playerUuid().toString(), "balanceCents", receipt.balanceCents()));
                    }
                } catch (Exception e) { result.completeExceptionally(e); }
            });
            send(exchange, 200, result.get(10, TimeUnit.SECONDS));
        } catch (Exception exception) {
            Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
            int status = cause instanceof ExchangeReceipt.ExchangeConflict ? 409
                    : cause instanceof NoSuchElementException ? 404
                    : cause instanceof IllegalArgumentException || cause instanceof ArithmeticException ? 400 : 503;
            String message = status == 503 ? "金币接口暂不可用；结果可能已提交，只能使用原 requestId 重试"
                    : cause.getMessage();
            send(exchange, status, Map.of("success", false, "error", message == null ? "请求无效" : message));
        } finally { exchange.close(); }
    }

    private static String text(JsonObject request, String key) {
        if (!request.has(key) || request.get(key).isJsonNull()) return "";
        if (!request.get(key).isJsonPrimitive() || !request.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " 必须为字符串");
        }
        return request.get(key).getAsString();
    }

    private static long exactLong(JsonObject request, String key) {
        try {
            if (!request.has(key) || !request.get(key).isJsonPrimitive() || !request.get(key).getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(key + " 必须为整数");
            }
            return request.get(key).getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException(key + " 必须为有效整数"); }
    }

    private static void send(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = ServerDataManager.GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() {
        accepting = false;
        http.stop(0);
        workers.shutdownNow();
    }
}
