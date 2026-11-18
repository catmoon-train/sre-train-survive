package io.wifi.habitrainsurvive.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.wifi.habitrainsurvive.config.CoreConfigManager;
import java.io.IOException;
import java.nio.file.*;

/** Generates an always-enabled server data pack before dynamic registries are loaded. */
public final class WorldBootstrap {
    private static Path packRoot;
    private WorldBootstrap() {}
    public static synchronized Path pack() {
        if (packRoot != null) return packRoot;
        try {
            WorldManager.get().loadConfiguration(CoreConfigManager.directory().resolve("worlds.json"));
            Path root = CoreConfigManager.directory().resolve("generated-world-pack");
            WorldPaths.rejectLinks(CoreConfigManager.directory().toAbsolutePath(), root.toAbsolutePath());
            CoreConfigManager.write(root.resolve("pack.mcmeta"), JsonParser.parseString("{\"pack\":{\"pack_format\":48,\"description\":\"Habi managed worlds\"}}"));
            Path definitions = root.resolve("data/habi_train_survive/dimension");
            WorldPaths.rejectLinks(root.toAbsolutePath(), definitions.toAbsolutePath());
            if (Files.isDirectory(definitions, LinkOption.NOFOLLOW_LINKS)) try (var files = Files.walk(definitions)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    WorldPaths.rejectLinks(root.toAbsolutePath(), file.toAbsolutePath());
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    String relative = definitions.relativize(file).toString().replace('\\', '/');
                    String id = "habi_train_survive:" + relative.substring(0, relative.length() - 5);
                    if (!WorldManager.get().startupEnabled(id)) Files.delete(file);
                }
            }
            for (WorldDefinition d : WorldManager.get().all()) if (d.managed() && d.enabled) {
                String path = d.id.split(":", 2)[1];
                CoreConfigManager.write(root.resolve("data/habi_train_survive/dimension/" + path + ".json"), dimension(d));
            }
            packRoot = root;
            return root;
        } catch (IOException e) { throw new IllegalStateException("无法初始化世界定义（未覆盖错误配置）", e); }
    }
    public static JsonObject dimension(WorldDefinition d) {
        String type = d.preset.equals("nether") ? "the_nether" : d.preset.equals("end") ? "the_end" : "overworld";
        JsonObject json = new JsonObject(); json.addProperty("type", "minecraft:" + type);
        JsonObject generator = new JsonObject();
        if (d.preset.equals("flat") || d.preset.equals("void")) {
            generator.addProperty("type", "minecraft:flat");
            String layers = d.preset.equals("void") ? "[]" : "[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}]";
            generator.add("settings", JsonParser.parseString("{\"biome\":\"minecraft:" + (d.preset.equals("void") ? "the_void" : "plains") + "\",\"lakes\":false,\"features\":false,\"structure_overrides\":[],\"layers\":" + layers + "}"));
        } else {
            generator.addProperty("type", "minecraft:noise");
            generator.addProperty("settings", "minecraft:" + (d.preset.equals("normal") ? "overworld" : d.preset));
            if (d.preset.equals("end")) generator.add("biome_source", JsonParser.parseString("{\"type\":\"minecraft:the_end\"}"));
            else generator.add("biome_source", JsonParser.parseString("{\"type\":\"minecraft:multi_noise\",\"preset\":\"minecraft:" + (d.preset.equals("nether") ? "nether" : "overworld") + "\"}"));
        }
        json.add("generator", generator); return json;
    }
}
