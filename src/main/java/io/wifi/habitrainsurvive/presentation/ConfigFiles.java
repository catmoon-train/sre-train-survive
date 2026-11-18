package io.wifi.habitrainsurvive.presentation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Checked UTF-8 persistence: a failed write never replaces the last valid document. */
public final class ConfigFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigFiles() { }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T value = GSON.fromJson(reader, type);
            if (value == null) throw new IOException(file + ": JSON must not be null");
            return value;
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
    }

    public static void installDefault(Path file, String resource) throws IOException {
        if (Files.exists(file)) return;
        try (var input = ConfigFiles.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing bundled configuration " + resource);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.copy(input, file);
        }
    }

    public static void write(Path file, Object value) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void text(String value, String field, int maximum, boolean allowBlank) {
        if (value == null || value.length() > maximum || (!allowBlank && value.isBlank())
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + ": expected " + (allowBlank ? "" : "nonblank ")
                    + "text of at most " + maximum + " characters");
        }
    }
}
