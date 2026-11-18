package io.wifi.habitrainsurvive.config;

import io.wifi.habitrainsurvive.HabiTrainSurvive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modular feature toggles stored in config/habi_train_survive/modules.json.
 * All modules default to enabled (true).
 */
public final class ModuleConfig {
    public static final class Data {
        public Map<String, Boolean> modules = new LinkedHashMap<>();

        public Data() {
            for (ModuleId id : ModuleId.values()) {
                modules.put(id.id(), Boolean.TRUE);
            }
        }
    }

    private static volatile boolean[] enabledFlags = defaultFlags();
    private static Data current = new Data();
    private static Path configFile;

    private ModuleConfig() {
    }

    private static boolean[] defaultFlags() {
        boolean[] flags = new boolean[ModuleId.values().length];
        Arrays.fill(flags, true);
        return flags;
    }

    public static synchronized void load() {
        load(CoreConfigManager.directory().resolve("modules.json"));
    }

    public static synchronized void load(Path file) {
        configFile = file;
        boolean existed = Files.exists(file);
        try {
            Data loaded = CoreConfigManager.load(file, Data.class, new Data(), data -> {
                if (data.modules == null) {
                    data.modules = new LinkedHashMap<>();
                }
            });
            boolean changed = fillMissingDefaults(loaded);
            current = loaded;
            refreshFlags(loaded);
            if (existed && changed) {
                CoreConfigManager.write(file, loaded);
            }
        } catch (IOException e) {
            HabiTrainSurvive.LOGGER.error("模块开关配置加载失败，将继续使用当前状态: {}", e.getMessage());
        }
    }

    public static synchronized void reload() throws IOException {
        Path file = configFile != null ? configFile : CoreConfigManager.directory().resolve("modules.json");
        boolean existed = Files.exists(file);
        Data loaded = CoreConfigManager.load(file, Data.class, new Data(), data -> {
            if (data.modules == null) {
                data.modules = new LinkedHashMap<>();
            }
        });
        boolean changed = fillMissingDefaults(loaded);
        current = loaded;
        refreshFlags(loaded);
        if (existed && changed) {
            CoreConfigManager.write(file, loaded);
        }
    }

    private static boolean fillMissingDefaults(Data data) {
        boolean changed = false;
        Map<String, Boolean> normalized = new LinkedHashMap<>();
        for (ModuleId id : ModuleId.values()) {
            Boolean value = data.modules.get(id.id());
            if (value == null) {
                value = Boolean.TRUE;
                changed = true;
            }
            normalized.put(id.id(), value);
        }
        for (Map.Entry<String, Boolean> entry : data.modules.entrySet()) {
            if (!normalized.containsKey(entry.getKey()) && entry.getValue() != null) {
                normalized.put(entry.getKey(), entry.getValue());
            }
        }
        data.modules = normalized;
        return changed;
    }

    private static void refreshFlags(Data data) {
        boolean[] next = new boolean[ModuleId.values().length];
        for (ModuleId id : ModuleId.values()) {
            Boolean val = data.modules.get(id.id());
            next[id.ordinal()] = val == null || val;
        }
        enabledFlags = next;
    }

    public static boolean isEnabled(ModuleId id) {
        return id == null || enabledFlags[id.ordinal()];
    }

    public static synchronized void setEnabled(ModuleId id, boolean enabled) throws IOException {
        if (id == null) return;
        current.modules.put(id.id(), enabled);
        refreshFlags(current);
        Path file = configFile != null ? configFile : CoreConfigManager.directory().resolve("modules.json");
        CoreConfigManager.write(file, current);
    }

    public static synchronized boolean toggle(ModuleId id) throws IOException {
        boolean next = !isEnabled(id);
        setEnabled(id, next);
        return next;
    }
}
