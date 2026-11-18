package io.wifi.habitrainsurvive.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import io.wifi.habitrainsurvive.config.CoreConfigManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.io.IOException;
import java.nio.file.Path;

public final class NaturalSpawnManager {
    private static final NaturalSpawnManager INSTANCE = new NaturalSpawnManager();
    private volatile NaturalSpawnConfig config = new NaturalSpawnConfig();
    private Path file;

    private NaturalSpawnManager() {}
    public static NaturalSpawnManager get() { return INSTANCE; }

    public void load(Path path) throws IOException {
        NaturalSpawnConfig loaded = CoreConfigManager.load(path, NaturalSpawnConfig.class, new NaturalSpawnConfig(), value -> {});
        config = loaded;
        file = path;
    }

    public void reload() throws IOException {
        if (file == null) throw new IOException("自然刷新配置尚未加载");
        load(file);
    }

    public void setEnabled(boolean enabled) throws IOException {
        if (file == null) throw new IOException("自然刷新配置尚未加载");
        NaturalSpawnConfig next = new NaturalSpawnConfig();
        next.enabled = enabled;
        // Publish only after a successful write, retaining the live policy on failure.
        CoreConfigManager.write(file, next);
        config = next;
    }

    public boolean enabled() { return config.enabled; }

    public boolean skip(ServerLevelAccessor level, MobCategory category, RandomSource random) {
        NaturalSpawnConfig current = config;
        boolean overworld = level.getLevel().dimension().equals(Level.OVERWORLD);
        if (!current.enabled || !overworld) return false;
        boolean monster = category == MobCategory.MONSTER;
        boolean animal = !monster && category != MobCategory.MISC;
        return current.skip(true, monster, animal, animal && random.nextBoolean());
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("worldspawn").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(status()), false);
                    return 1;
                })
                .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                    try {
                        setEnabled(BoolArgumentType.getBool(context, "enabled"));
                    } catch (IOException exception) {
                        context.getSource().sendFailure(Component.literal("配置保存失败，规则未更改：" + exception.getMessage()));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal(status() + "（已保存，即时生效）"), true);
                    return 1;
                })));
    }

    private String status() {
        return "主世界自然刷新限制：" + (enabled() ? "开启（怪物禁止，动物刷新机会减少50%）" : "关闭（遵循原版规则）")
                + "；不影响其他维度、现有生物、刷怪笼、召唤或繁殖。";
    }
}
