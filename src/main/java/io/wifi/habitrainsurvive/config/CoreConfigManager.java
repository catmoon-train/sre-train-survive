package io.wifi.habitrainsurvive.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Consumer;

/** Strict configuration reads and atomic writes. Never replace a broken config with defaults. */
public final class CoreConfigManager {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private CoreConfigManager() {}

    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve(HabiTrainSurvive.MOD_ID);
    }

    public static <T> T load(Path file, Class<T> type, T defaults, Consumer<T> validator) throws IOException {
        T value = defaults;
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                value = GSON.fromJson(reader, type);
            } catch (JsonParseException e) {
                throw new IOException(file + ": JSON 格式错误: " + e.getMessage(), e);
            }
        }
        try {
            if (value == null) throw new IllegalArgumentException("配置不能为 null");
            validator.accept(value);
        } catch (RuntimeException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
        if (!Files.exists(file)) write(file, value);
        return value;
    }

    public static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    /** Reset journal: force contents before rename; no non-atomic fallback is permitted. */
    public static void writeAtomic(Path file, Object value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
