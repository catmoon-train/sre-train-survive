package io.wifi.habitrainsurvive.world;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

/** Startup-only transactions. Call before opening ANY dimension, with an atomic, durable state writer. */
public final class ResourceResetManager {
    private ResourceResetManager() {}

    @FunctionalInterface
    public interface IOAction { void run() throws IOException; }

    public static boolean eligible(WorldDefinition definition) {
        if (definition == null || !"resource".equals(definition.kind)
                || !definition.managed() || "habi_train_survive:hub".equals(definition.id)) return false;
        try {
            validateFilesystemId(definition.id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static void applyPending(Path saveRoot, WorldConfig config, WorldState state, IOAction persist) throws IOException {
        Path root = saveRoot.toAbsolutePath().normalize();
        WorldPaths.rejectLinks(root, root);
        Map<String, WorldDefinition> definitions = new HashMap<>();
        for (WorldDefinition d : config.worlds) {
            if (d == null || definitions.put(d.id, d) != null) throw new IOException("重复或空世界定义");
        }
        // Validate the entire queue before moving the first world, including orphaned/hostile state entries.
        for (var entry : state.worlds.entrySet()) {
            WorldState.Runtime runtime = entry.getValue();
            if (runtime == null) throw new IOException("空世界状态: " + entry.getKey());
            if (runtime.resetBackup != null && !runtime.pendingReset) throw new IOException("重置日志缺少 pendingReset");
            if (!runtime.pendingReset) continue;
            WorldDefinition d = definitions.get(entry.getKey());
            if (!eligible(d)) throw new IOException("拒绝重置受保护或未知世界: " + entry.getKey());
            Path target = dimension(root, d, config);
            checkTree(target);
            if (runtime.generation < 0 || runtime.generation == Long.MAX_VALUE) throw new IOException("重置代次溢出");
            if (runtime.resetBackup != null) {
                if (runtime.resetGeneration != runtime.generation + 1) throw new IOException("重置日志代次不匹配");
                checkTree(loggedBackup(root, d, runtime.resetBackup));
            }
        }
        for (var entry : state.worlds.entrySet()) {
            WorldState.Runtime runtime = entry.getValue();
            if (runtime.pendingReset) reset(root, definitions.get(entry.getKey()), config, runtime, persist);
        }
    }

    private static void reset(Path root, WorldDefinition d, WorldConfig config, WorldState.Runtime r, IOAction persist) throws IOException {
        Path target = dimension(root, d, config);
        if (r.resetBackup == null) {
            Path backup = WorldPaths.backup(root, d.id, System.currentTimeMillis());
            // Millisecond collisions, including a completed earlier transaction, must never overwrite a backup.
            long stamp = System.currentTimeMillis();
            while (exists(backup)) backup = WorldPaths.backup(root, d.id, ++stamp);
            r.resetBackup = root.relativize(backup).toString().replace('\\', '/');
            r.resetHadDirectory = exists(target);
            r.resetGeneration = r.generation + 1;
            try {
                persist.run(); // WAL must be durable BEFORE even attempting the move.
            } catch (IOException e) {
                r.resetBackup = null;
                r.resetHadDirectory = false;
                r.resetGeneration = 0;
                throw e;
            }
        }
        Path backup = loggedBackup(root, d, r.resetBackup);
        WorldPaths.rejectLinks(root, target);
        checkTree(target);
        checkTree(backup);
        boolean sourceExists = exists(target), backupExists = exists(backup);
        if (r.resetHadDirectory) {
            if (sourceExists == backupExists) throw new IOException("重置状态歧义（目标与备份同时存在或同时缺失）: " + d.id);
            if (sourceExists) {
                Files.createDirectories(backup.getParent());
                WorldPaths.rejectLinks(root, backup);
                atomicMove(target, backup);
            }
            // Source absent + backup present is the only valid post-move crash recovery.
        } else if (sourceExists || backupExists) {
            throw new IOException("空世界重置日志与磁盘不符，拒绝移动新世界: " + d.id);
        }

        String journal = r.resetBackup;
        boolean hadDirectory = r.resetHadDirectory;
        long nextGeneration = r.resetGeneration, oldGeneration = r.generation;
        long oldReset = r.lastReset, oldTime = r.dayTime;
        var oldSpawn = r.spawn;
        r.generation = nextGeneration;
        r.lastReset = System.currentTimeMillis();
        r.dayTime = 0;
        r.spawn = null;
        r.pendingReset = false;
        r.resetBackup = null;
        r.resetHadDirectory = false;
        r.resetGeneration = 0;
        try {
            persist.run();
        } catch (IOException e) {
            // Keep the WAL in memory as well as on disk. Never roll the directory back over a new world.
            r.generation = oldGeneration;
            r.lastReset = oldReset;
            r.dayTime = oldTime;
            r.spawn = oldSpawn;
            r.pendingReset = true;
            r.resetBackup = journal;
            r.resetHadDirectory = hadDirectory;
            r.resetGeneration = nextGeneration;
            throw e;
        }
    }

    private static Path loggedBackup(Path root, WorldDefinition d, String relative) throws IOException {
        // Do not resolve arbitrary state JSON paths. Reconstruct a single, world-specific backup child.
        String prefix = "habi-backups/" + d.id.replace(':', '_').replace('/', '_') + "-";
        if (!relative.startsWith(prefix) || !relative.substring(prefix.length()).matches("[0-9]{1,19}"))
            throw new IOException("非法重置备份日志路径: " + relative);
        try {
            long stamp = Long.parseLong(relative.substring(prefix.length()));
            Path backup = WorldPaths.backup(root, d.id, stamp);
            if (!root.relativize(backup).toString().replace('\\', '/').equals(relative)
                    || !backup.getParent().equals(root.resolve("habi-backups"))) throw new IOException("备份路径越界");
            return backup;
        } catch (IllegalArgumentException e) {
            throw new IOException("非法备份时间戳", e);
        }
    }

    /** Also reject nested managed dimensions: moving a parent would otherwise move an unrelated world. */
    static Path dimension(Path root, WorldDefinition d, WorldConfig config) throws IOException {
        try {
            validateFilesystemId(d.id);
            Path target = WorldPaths.dimension(root, d.id);
            for (WorldDefinition other : config.worlds) {
                if (other == null || other == d || !other.managed()) continue;
                validateFilesystemId(other.id);
                Path path = WorldPaths.dimension(root, other.id);
                if (target.startsWith(path) || path.startsWith(target)) throw new IOException("维度目录重叠: " + d.id + " / " + other.id);
            }
            return target;
        } catch (IllegalArgumentException e) {
            throw new IOException("非法维度路径", e);
        }
    }

    private static void validateFilesystemId(String id) {
        WorldDefinition.validateId(id);
        for (String part : id.split(":", 2)[1].split("/"))
            if (part.endsWith(".") || part.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))
                throw new IllegalArgumentException("禁止可能别名到其他目录的 Windows 路径: " + id);
    }

    static boolean exists(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException e) {
            return false; // Permission/IO failures are not evidence that an existing map is absent.
        }
    }

    /** No follow-links traversal; reject special files as well as symlinks, including the root itself. */
    static void checkTree(Path path) throws IOException {
        WorldPaths.rejectLinks(path, path);
        if (!exists(path)) return;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("不是目录: " + path);
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("目录中包含链接或特殊文件: " + file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void atomicMove(Path source, Path target) throws IOException {
        WorldPaths.rejectLinks(source, source);
        WorldPaths.rejectLinks(target, target);
        if (exists(target)) throw new IOException("拒绝覆盖已存在的目录: " + target);
        if (!Files.getFileStore(source).equals(Files.getFileStore(target.getParent()))) throw new IOException("事务目录必须位于同一文件系统");
        // ATOMIC_MOVE is mandatory. In particular, never fall back to copy/delete or REPLACE_EXISTING.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }
}
