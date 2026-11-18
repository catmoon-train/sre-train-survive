package io.wifi.habitrainsurvive.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static io.wifi.habitrainsurvive.world.ResourceResetManager.*;

/** Offline Anvil import; does not load Minecraft registries, data fixers or any player data. */
public final class WorldImporter {
    private static final int MIN_VERSION = 3463, MAX_VERSION = 3955; // 1.20 through 1.21.1
    private static final String MARKER = ".habi-anvil-import.json";
    private static final Set<String> PRESETS = Set.of("normal", "nether", "end", "flat", "void");
    private static final Set<String> PRIVATE = Set.of("playerdata", "players", "advancements", "stats", "session.lock", "level.dat", "level.dat_old");
    private static final Pattern REGION = Pattern.compile("r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca");
    private static final Pattern EXTERNAL = Pattern.compile("c\\.-?[0-9]+\\.-?[0-9]+\\.mcc");
    private WorldImporter() {}

    /** Invoke after applyPending, before opening any dimension. persist must atomically/durably save state. */
    public static void applyImports(Path saveRoot, Path serverRoot, WorldConfig config, WorldState state,
                                    ResourceResetManager.IOAction persist) throws IOException {
        Path root = saveRoot.toAbsolutePath().normalize();
        WorldPaths.rejectLinks(root, root);
        Set<String> ids = new HashSet<>();
        for (WorldDefinition d : config.worlds) {
            if (d == null || !ids.add(d.id)) throw new IOException("重复或空世界定义");
            if (d.importSource == null || !d.enabled) continue;
            if (!d.managed()) throw new IOException("只能导入启用的本核心世界");
            Path target = dimension(root, d, config);
            WorldState.Runtime r = state.world(d.id);
            if (r.pendingReset || r.resetBackup != null) throw new IOException("必须先完成资源重置");
            if (r.imported) continue; // A configured one-shot import must not run again after a resource reset.
            if (r.hubBuilt) throw new IOException("不能覆盖已经建设的主城: " + d.id);
            if (r.generation < 0 || !PRESETS.contains(d.preset)) throw new IOException("无效导入世界定义");
            validateRelative(d.importSource);
            if (d.seed == null) throw new IOException("导入必须先在 worlds.json 显式配置与地图一致的 seed，不能猜测续生成种子");
            Path journal = guarded(root, "habi-import-journals/" + hex(hash().digest(d.id.getBytes(StandardCharsets.UTF_8))) + ".json");
            if (exists(journal)) {
                recover(root, target, journal, d, r, persist);
                continue;
            }
            if (exists(target)) throw new IOException("导入目标已存在，拒绝覆盖: " + target);
            Path source = WorldPaths.importDirectory(serverRoot, d.importSource);
            checkTree(source);
            Metadata metadata = metadata(source, d);
            validateAnvil(source);
            Path stagingRoot = guarded(root, "habi-import-staging");
            if (source.startsWith(stagingRoot) || stagingRoot.startsWith(source)
                    || source.startsWith(target) || target.startsWith(source)) throw new IOException("导入源与事务目录重叠");
            Files.createDirectories(stagingRoot);
            WorldPaths.rejectLinks(root, stagingRoot);
            String token = UUID.randomUUID().toString();
            Path staging = guarded(root, "habi-import-staging/" + token);
            Files.createDirectory(staging);
            // Incomplete, unjournaled staging is never trusted or deleted on restart. A new UUID is used instead.
            copyPayload(source, staging);
            validateAnvil(staging);
            JsonObject plan = new JsonObject();
            plan.addProperty("format", 1);
            plan.addProperty("kind", "habi-anvil-import");
            plan.addProperty("id", d.id);
            plan.addProperty("source", d.importSource);
            plan.addProperty("preset", d.preset);
            plan.addProperty("seed", d.seed);
            plan.addProperty("version", metadata.version());
            plan.addProperty("generation", r.generation);
            plan.addProperty("token", token);
            plan.addProperty("digest", payloadDigest(staging));
            writeNew(staging.resolve(MARKER), plan);
            Files.createDirectories(journal.getParent());
            WorldPaths.rejectLinks(root, journal);
            // Atomic publication prevents a partially written journal from authorizing recovery.
            Path temporary = guarded(root, "habi-import-journals/" + token + ".tmp");
            writeNew(temporary, plan);
            atomicMove(temporary, journal);
            recover(root, target, journal, d, r, persist);
        }
    }

    private static void recover(Path root, Path target, Path journal, WorldDefinition d, WorldState.Runtime r,
                                ResourceResetManager.IOAction persist) throws IOException {
        JsonObject plan = readJson(journal);
        if (number(plan, "format") != 1 || !"habi-anvil-import".equals(text(plan, "kind"))
                || !d.id.equals(text(plan, "id")) || !d.importSource.equals(text(plan, "source"))
                || !d.preset.equals(text(plan, "preset")) || number(plan, "seed") != d.seed
                || number(plan, "generation") != r.generation) throw new IOException("导入事务与当前世界配置/代次不符");
        version(number(plan, "version"));
        String token = text(plan, "token"), digest = text(plan, "digest");
        if (!token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !digest.matches("[0-9a-f]{64}")) throw new IOException("非法导入事务标记");
        // Stored JSON never supplies filesystem paths: derive them from the validated token and config.
        Path staging = guarded(root, "habi-import-staging/" + token);
        WorldPaths.rejectLinks(root, target);
        boolean hasTarget = exists(target), hasStaging = exists(staging);
        if (hasTarget == hasStaging) throw new IOException("导入恢复歧义（目标与 staging 同时存在或同时缺失）");
        Path complete = hasTarget ? target : staging;
        checkTree(complete);
        if (!readJson(complete.resolve(MARKER)).equals(plan) || !payloadDigest(complete).equals(digest))
            throw new IOException("导入标记/文件摘要不匹配，拒绝把已有地图视为已导入");
        validateAnvil(complete);
        if (!hasTarget) {
            Files.createDirectories(target.getParent());
            WorldPaths.rejectLinks(root, target);
            atomicMove(staging, target);
        }
        r.imported = true;
        try {
            persist.run();
        } catch (IOException e) {
            r.imported = false;
            throw e;
        }
        // Retain the journal and unique marker as provenance; retries with imported=true are no-ops.
    }

    private static Path guarded(Path root, String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("导入事务路径越界");
        WorldPaths.rejectLinks(root, path);
        return path;
    }

    private static void validateRelative(String relative) throws IOException {
        if (relative.isBlank() || relative.contains(":") || relative.contains("\\") || relative.startsWith("/"))
            throw new IOException("importSource 必须是 imports 下的相对目录");
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("importSource 禁止路径穿越");
    }

    private record Metadata(int version) {}

    /** manifest.json: {"format":1,"preset":"normal","version":3955,"seed":12345}. */
    private static Metadata metadata(Path source, WorldDefinition d) throws IOException {
        JsonObject manifest = exists(source.resolve("manifest.json")) ? readJson(source.resolve("manifest.json")) : null;
        Long declaredSeed = null;
        Integer declaredVersion = null;
        if (manifest != null) {
            if (number(manifest, "format") != 1 || !d.preset.equals(text(manifest, "preset"))) throw new IOException("manifest preset 不匹配");
            declaredSeed = number(manifest, "seed");
            declaredVersion = version(number(manifest, "version"));
            if (!declaredSeed.equals(d.seed)) throw new IOException("manifest seed 与显式配置不匹配");
        }
        if (exists(source.resolve("level.dat"))) {
            Map<String, Object> data = compound(readLevel(source.resolve("level.dat")).get("Data"));
            if (!(data.get("DataVersion") instanceof Integer dataVersion)) throw new IOException("level.dat 缺少 DataVersion");
            int actualVersion = version(dataVersion);
            if (declaredVersion != null && declaredVersion != actualVersion) throw new IOException("manifest 与 level.dat 版本不匹配");
            Map<String, Object> settings = compound(data.get("WorldGenSettings"));
            Object actualSeed = settings.get("seed");
            if (actualSeed == null) actualSeed = data.get("RandomSeed");
            if (actualSeed != null && (!(actualSeed instanceof Long) || !actualSeed.equals(d.seed))) throw new IOException("level.dat seed 与显式配置不匹配");
            // A full world's root/region is overworld. Extracted nether/end directories need an explicit manifest.
            String detected = detectPreset(settings, manifest == null ? "normal" : d.preset);
            if (detected != null && !detected.equals(d.preset)) throw new IOException("level.dat 维度类型/生成器与 preset 不匹配");
            if (manifest == null && (detected == null || actualSeed == null)) throw new IOException("元数据不足；请提供明确的 manifest.json preset/version/seed");
            return new Metadata(actualVersion);
        }
        if (manifest == null) throw new IOException("缺少 level.dat 元数据；请提供 manifest.json preset/version/seed");
        return new Metadata(declaredVersion);
    }

    private static int version(long value) throws IOException {
        if (value < MIN_VERSION || value > MAX_VERSION) throw new IOException("仅支持 DataVersion " + MIN_VERSION + ".." + MAX_VERSION + " 的 Anvil 地图");
        return (int) value;
    }

    private static String detectPreset(Map<String, Object> settings, String selected) throws IOException {
        String key = selected.equals("nether") ? "minecraft:the_nether" : selected.equals("end") ? "minecraft:the_end" : "minecraft:overworld";
        Map<String, Object> dimension = compound(compound(settings.get("dimensions")).get(key));
        Map<String, Object> generator = compound(dimension.get("generator"));
        Object type = dimension.get("type"), generatorType = generator.get("type");
        String expectedType = selected.equals("nether") ? "minecraft:the_nether" : selected.equals("end") ? "minecraft:the_end" : "minecraft:overworld";
        if (type != null && !expectedType.equals(type)) throw new IOException("不兼容的维度类型: " + type);
        if (type == null || generatorType == null) return null;
        if ("minecraft:noise".equals(generatorType)) {
            String preset = switch (String.valueOf(generator.get("settings"))) {
                case "minecraft:overworld" -> "normal";
                case "minecraft:nether" -> "nether";
                case "minecraft:end" -> "end";
                default -> throw new IOException("不支持自定义 noise settings，请先转换地图");
            };
            if (!key.equals(preset.equals("nether") ? "minecraft:the_nether" : preset.equals("end") ? "minecraft:the_end" : "minecraft:overworld"))
                throw new IOException("维度类型与 noise settings 冲突");
            return preset;
        }
        if ("minecraft:flat".equals(generatorType) && "minecraft:overworld".equals(type)) {
            Object layers = compound(generator.get("settings")).get("layers");
            if (layers instanceof List<?> list) return list.isEmpty() ? "void" : "flat";
            return null;
        }
        throw new IOException("不支持自定义生成器: " + generatorType);
    }

    private static void validateAnvil(Path source) throws IOException {
        boolean hasChunks = false;
        for (String name : List.of("region", "entities", "poi")) {
            Path directory = source.resolve(name);
            if (!exists(directory)) {
                if (name.equals("region")) throw new IOException("缺少 region 目录");
                continue;
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("不是 Anvil 目录: " + directory);
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    String filename = file.getFileName().toString();
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Anvil 目录仅允许普通文件: " + file);
                    if (REGION.matcher(filename).matches()) {
                        boolean nonempty = regionHasChunks(file);
                        if (name.equals("region")) hasChunks |= nonempty;
                    } else if (!EXTERNAL.matcher(filename).matches() || Files.size(file) == 0) {
                        throw new IOException("非标准 Anvil 文件: " + file);
                    }
                }
            }
        }
        if (!hasChunks) throw new IOException("region 中没有可导入的区块数据");
    }

    private static boolean regionHasChunks(Path file) throws IOException {
        long size = Files.size(file);
        if (size < 8192 || size % 4096 != 0) throw new IOException("无效 Anvil 扇区大小: " + file);
        var matcher = REGION.matcher(file.getFileName().toString());
        if (!matcher.matches()) throw new IOException("非法 region 文件名");
        long regionX, regionZ;
        try {
            regionX = Integer.parseInt(matcher.group(1));
            regionZ = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) { throw new IOException("region 坐标溢出", e); }
        boolean hasChunks = false;
        Set<Long> used = new HashSet<>();
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            int[] locations = new int[1024];
            for (int i = 0; i < locations.length; i++) locations[i] = input.readInt();
            for (int i = 0; i < locations.length; i++) {
                int offset = locations[i] >>> 8, count = locations[i] & 255;
                if (locations[i] == 0) continue;
                if (offset < 2 || count == 0 || (long) offset + count > size / 4096) throw new IOException("无效 Anvil 区块位置: " + file);
                for (long sector = offset; sector < (long) offset + count; sector++)
                    if (!used.add(sector)) throw new IOException("Anvil 区块扇区重叠");
                input.seek((long) offset * 4096);
                int length = input.readInt(), compression = input.readUnsignedByte();
                int codec = compression & 127;
                if (length < 1 || length > count * 4096 - 4 || codec < 1 || codec > 4) throw new IOException("无效 Anvil 区块头");
                if ((compression & 128) != 0) {
                    Path external = file.resolveSibling("c." + (regionX * 32 + i % 32) + "." + (regionZ * 32 + i / 32) + ".mcc");
                    if (length != 1 || !Files.isRegularFile(external, LinkOption.NOFOLLOW_LINKS) || Files.size(external) == 0)
                        throw new IOException("缺少外部区块数据: " + external);
                } else if (length == 1) throw new IOException("区块 payload 为空");
                hasChunks = true;
            }
        }
        return hasChunks;
    }

    private static void copyPayload(Path source, Path staging) throws IOException {
        for (String name : List.of("region", "entities", "poi", "data")) {
            Path from = source.resolve(name);
            if (!exists(from)) continue;
            if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) throw new IOException("导入组件不是目录: " + from);
            Path to = staging.resolve(name);
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (PRIVATE.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) return FileVisitResult.SKIP_SUBTREE;
                    Files.createDirectory(to.resolve(from.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) throw new IOException("拒绝复制链接/特殊文件");
                    if (PRIVATE.contains(file.getFileName().toString().toLowerCase(Locale.ROOT))) return FileVisitResult.CONTINUE;
                    Path destination = to.resolve(from.relativize(file));
                    WorldPaths.rejectLinks(source, file);
                    Files.copy(file, destination, LinkOption.NOFOLLOW_LINKS);
                    try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static String payloadDigest(Path root) throws IOException {
        MessageDigest digest = hash();
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !path.equals(root.resolve(MARKER)))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/'))).toList();
        }
        byte[] buffer = new byte[65536];
        for (Path file : files) {
            digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(Files.size(file)).array());
            MessageDigest content = hash();
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                for (int count; (count = input.read(buffer)) != -1;) content.update(buffer, 0, count);
            }
            digest.update(content.digest());
        }
        return hex(digest.digest());
    }

    private static MessageDigest hash() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

    private static void writeNew(Path file, JsonObject json) throws IOException {
        WorldPaths.rejectLinks(file, file);
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static JsonObject readJson(Path file) throws IOException {
        WorldPaths.rejectLinks(file, file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536) throw new IOException("非法/过大的导入元数据: " + file);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) throw new IOException("导入元数据不是 JSON 对象");
            return element.getAsJsonObject();
        } catch (RuntimeException e) { throw new IOException("损坏的导入元数据: " + file, e); }
    }
    private static String text(JsonObject json, String key) throws IOException {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("缺少字符串字段: " + key);
        return value.getAsString();
    }
    private static long number(JsonObject json, String key) throws IOException {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("缺少整数字段: " + key);
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IOException("无效整数: " + key, e); }
    }

    // Small bounded NBT reader: no Minecraft static initialization, registries or third-party NBT dependency.
    private static Map<String, Object> readLevel(Path file) throws IOException {
        byte[] bytes;
        try (InputStream gzip = new GZIPInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            bytes = gzip.readNBytes(16 * 1024 * 1024 + 1);
        }
        if (bytes.length > 16 * 1024 * 1024) throw new IOException("level.dat 解压后超过16MiB");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readUnsignedByte() != 10) throw new IOException("level.dat 根不是 NBT compound");
            input.readUTF();
            Map<String, Object> root = compound(readTag(input, 10, 0));
            if (input.read() != -1) throw new IOException("level.dat 包含多余 NBT 数据");
            return root;
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> compound(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
    private static int length(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 1_000_000) throw new IOException("NBT 数组过大/负长度");
        return length;
    }
    private static Object readTag(DataInputStream input, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT 层级过深");
        return switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> { input.skipNBytes(length(input)); yield null; }
            case 8 -> input.readUTF();
            case 9 -> {
                int elementType = input.readUnsignedByte(), count = length(input);
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < count; i++) list.add(readTag(input, elementType, depth + 1));
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new HashMap<>();
                for (int child; (child = input.readUnsignedByte()) != 0;) {
                    String name = input.readUTF();
                    if (map.containsKey(name)) throw new IOException("NBT 字段重复");
                    map.put(name, readTag(input, child, depth + 1));
                }
                yield map;
            }
            case 11 -> { input.skipNBytes((long) length(input) * 4); yield null; }
            case 12 -> { input.skipNBytes((long) length(input) * 8); yield null; }
            default -> throw new IOException("无效 NBT tag: " + type);
        };
    }
}
