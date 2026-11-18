package io.wifi.habitrainsurvive.world;

import java.io.IOException;
import java.nio.file.*;

/** Filesystem guards shared by imports, backups and tests. Symlinks and traversal fail closed. */
public final class WorldPaths {
    private WorldPaths() {}
    public static Path dimension(Path saveRoot, String id) throws IOException {
        WorldDefinition.validateId(id);
        if (!id.startsWith("habi_train_survive:")) throw new IOException("只允许操作本核心维度: " + id);
        Path base = saveRoot.toAbsolutePath().normalize();
        Path result = base.resolve("dimensions/habi_train_survive").resolve(id.split(":", 2)[1]).normalize();
        if (!result.startsWith(base.resolve("dimensions/habi_train_survive"))) throw new IOException("维度路径越界");
        rejectLinks(base, result);
        return result;
    }
    public static Path importDirectory(Path serverRoot, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.contains(":") || relative.contains("\\") || relative.startsWith("/"))
            throw new IOException("导入路径必须相对于 imports 目录");
        Path base = serverRoot.toAbsolutePath().normalize().resolve("imports");
        Path result = base.resolve(relative).normalize();
        if (!result.startsWith(base) || result.equals(base)) throw new IOException("导入路径越界");
        rejectLinks(base.getParent(), result);
        if (!Files.isDirectory(result.resolve("region"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("导入目录缺少 region（请选择对应维度的目录）");
        try (var stream = Files.walk(result)) {
            if (stream.anyMatch(Files::isSymbolicLink)) throw new IOException("导入目录中禁止符号链接");
        }
        return result;
    }
    public static void rejectLinks(Path root, Path target) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException("路径包含符号链接: " + current);
        }
        if (!target.toAbsolutePath().normalize().startsWith(absolute)) throw new IOException("路径不在允许目录内");
        for (Path part : absolute.relativize(target.toAbsolutePath().normalize())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("路径包含符号链接: " + current);
        }
    }
    public static Path backup(Path root, String id, long stamp) throws IOException {
        WorldDefinition.validateId(id);
        Path base = root.toAbsolutePath().normalize();
        Path path = base.resolve("habi-backups").resolve(id.replace(':', '_').replace('/', '_') + "-" + stamp);
        rejectLinks(base, path);
        return path;
    }
}
