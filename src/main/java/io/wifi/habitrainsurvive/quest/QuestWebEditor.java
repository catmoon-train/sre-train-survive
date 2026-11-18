package io.wifi.habitrainsurvive.quest;

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
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 任务在线编辑：绑定一个临时 HTTP 端口（默认 11088），用浏览器直接编辑并写回 quests.json。
 *
 * 安全约束：
 * - 每次启动生成一次性令牌，页面与所有写接口都必须携带，否则 401；
 * - 写回先在服务端线程校验整份任务定义，校验失败不落盘、不切换运行中的定义；
 * - 只提供任务编辑页面与任务读写接口，不暴露其他文件、不执行任意指令；
 * - 服务器停止或执行 /quest web stop 时关闭监听。
 */
public final class QuestWebEditor implements AutoCloseable {
    public static final int DEFAULT_PORT = 11088;
    public static final String CONFIG_FILE = "quest_web_editor.json";
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    public static final class Config {
        public String bindAddress = "0.0.0.0";
        public int port = DEFAULT_PORT;

        void validate() {
            if (bindAddress == null || bindAddress.isBlank()) throw new IllegalArgumentException("在线编辑监听地址不能为空");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("在线编辑端口必须为1到65535");
        }
    }

    private static QuestWebEditor active;

    private final MinecraftServer server;
    private final String token;
    private final HttpServer http;
    private final ExecutorService workers;
    private volatile boolean accepting = true;

    public static QuestWebEditor active() { return active; }

    /**
     * 启动（或按新端口重启）在线编辑器，返回访问地址。
     * port 为 null 时沿用配置文件里的端口，配置缺失则使用默认 11088。
     */
    public static String start(MinecraftServer server, Path directory, Integer port, String bindAddress) throws IOException {
        stop();
        Config config = readConfig(directory);
        if (port != null) config.port = port;
        if (bindAddress != null) config.bindAddress = bindAddress;
        config.validate();
        writeConfig(directory, config);
        QuestWebEditor editor = new QuestWebEditor(server, config);
        active = editor;
        return editor.url();
    }

    /** 读取在线编辑配置；文件缺失或无效时回退到默认端口，不覆盖已有文件。 */
    public static Config readConfig(Path directory) {
        Path file = directory.resolve(CONFIG_FILE);
        if (!Files.exists(file)) return new Config();
        try {
            Config loaded = ServerDataManager.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
            if (loaded == null) return new Config();
            if (loaded.bindAddress == null || loaded.bindAddress.isBlank()) loaded.bindAddress = "0.0.0.0";
            if (loaded.port < 1 || loaded.port > 65535) loaded.port = DEFAULT_PORT;
            return loaded;
        } catch (IOException | RuntimeException ignored) {
            return new Config();
        }
    }

    private static void writeConfig(Path directory, Config config) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(CONFIG_FILE), ServerDataManager.GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            HabiTrainSurvive.LOGGER.warn("任务在线编辑配置写入失败，本次运行仍使用已选端口: {}", exception.getMessage());
        }
    }

    public static void stop() {
        if (active != null) { active.close(); active = null; }
    }

    private QuestWebEditor(MinecraftServer server, Config config) throws IOException {
        this.server = server;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        this.token = HexFormat.of().formatHex(random);
        http = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 16);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
            Thread thread = new Thread(runnable, "habi-quest-editor");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        http.setExecutor(workers);
        http.createContext("/", this::handle);
        http.start();
    }

    public int port() { return http.getAddress().getPort(); }

    public String url() {
        String host = http.getAddress().getAddress().isLoopbackAddress() ? "127.0.0.1" : "服务器IP";
        return "http://" + host + ":" + port() + "/?token=" + token;
    }

    public String token() { return token; }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!accepting) { sendText(exchange, 503, "在线编辑正在关闭"); return; }
            if (!authorized(exchange)) { sendText(exchange, 401, "令牌无效：请使用启动时输出的完整链接"); return; }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            switch (path) {
                case "/", "/index.html" -> {
                    if (!"GET".equals(method)) { sendText(exchange, 405, "请使用 GET"); return; }
                    String html = onServerThread(() -> QuestExport.html(server) , 10);
                    send(exchange, 200, "text/html; charset=utf-8",
                            QuestExport.online(html, token));
                }
                case "/api/quests" -> {
                    if ("GET".equals(method)) {
                        String text = onServerThread(() -> Files.readString(questFile(), StandardCharsets.UTF_8), 10);
                        send(exchange, 200, "application/json; charset=utf-8", text);
                        return;
                    }
                    if (!"POST".equals(method)) { sendText(exchange, 405, "请使用 GET 或 POST"); return; }
                    byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                    if (body.length > MAX_BODY_BYTES) { sendText(exchange, 413, "任务文件过大"); return; }
                    String json = new String(body, StandardCharsets.UTF_8);
                    String error = onServerThread(() -> QuestManager.get().replaceDefinitions(json), 20);
                    if (error != null) { sendJson(exchange, 400, Map.of("success", false, "error", error)); return; }
                    sendJson(exchange, 200, Map.of("success", true));
                }
                case "/api/bundle" -> {
                    if (!"GET".equals(method)) { sendText(exchange, 405, "请使用 GET"); return; }
                    String json = onServerThread(() -> ServerDataManager.GSON.toJson(QuestExport.bundle(server)), 10);
                    send(exchange, 200, "application/json; charset=utf-8", json);
                }
                case "/api/close" -> {
                    if (!"POST".equals(method)) { sendText(exchange, 405, "请使用 POST"); return; }
                    accepting = false;
                    sendJson(exchange, 200, Map.of("success", true));
                    server.execute(QuestWebEditor::stop);
                }
                default -> sendText(exchange, 404, "页面不存在");
            }
        } catch (Exception exception) {
            Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            HabiTrainSurvive.LOGGER.warn("任务在线编辑请求失败: {} {}", exchange.getRequestURI(), message);
            sendJson(exchange, 500, Map.of("success", false, "error", message));
        } finally {
            exchange.close();
        }
    }

    private Path questFile() {
        Path file = QuestManager.get().definitionsPath();
        if (file == null) throw new IllegalStateException("任务系统尚未初始化");
        return file;
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("X-Quest-Token");
        if (supplied == null) {
            String query = exchange.getRequestURI().getQuery();
            if (query != null) for (String part : query.split("&")) {
                int split = part.indexOf('=');
                if (split > 0 && "token".equals(part.substring(0, split))) {
                    supplied = java.net.URLDecoder.decode(part.substring(split + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return supplied != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private <T> T onServerThread(Callable<T> action, int timeoutSeconds) throws Exception {
        if (server.isSameThread()) return action.call();
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try { future.complete(action.call()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future.get(timeoutSeconds, TimeUnit.SECONDS);
    }

    private static void sendText(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", message);
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", ServerDataManager.GSON.toJson(payload));
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() {
        accepting = false;
        http.stop(0);
        workers.shutdownNow();
    }
}
