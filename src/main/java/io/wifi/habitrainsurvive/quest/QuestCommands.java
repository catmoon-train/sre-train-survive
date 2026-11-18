package io.wifi.habitrainsurvive.quest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;

public final class QuestCommands {
    private QuestCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"quest", "quests", "mission"}) {
            dispatcher.register(Commands.literal(root)
                    .executes(ctx -> {
                        QuestManager.get().openGui(ctx.getSource().getPlayerOrException());
                        return 1;
                    })
                    .then(Commands.literal("submit")
                            .then(Commands.argument("task", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        ServerPlayer player = ctx.getSource().getPlayer();
                                        QuestCatalog catalog = QuestManager.get().catalog();
                                        if (player != null && catalog != null) {
                                            for (String id : QuestManager.get().rotationIds(player)) builder.suggest(id);
                                            for (QuestDefinition.Chapter chapter : catalog.chapters()) {
                                                for (QuestDefinition.Quest quest : chapter.quests) {
                                                    if (QuestManager.get().unlocked(player.getUUID(), quest.id)) builder.suggest(quest.id);
                                                }
                                            }
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                                        String error = QuestManager.get().submit(player, StringArgumentType.getString(ctx, "task"));
                                        if (error != null) {
                                            MessageUtil.sendError(player, "任务", error);
                                            return 0;
                                        }
                                        return 1;
                                    })))

                    .then(Commands.literal("reload")
                            .requires(QuestCommands::admin)
                            .executes(ctx -> {
                                String error = QuestManager.get().reload();
                                if (error != null) {
                                    ctx.getSource().sendFailure(Component.literal("任务重载失败，仍使用旧定义：\n" + error));
                                    return 0;
                                }
                                ctx.getSource().sendSuccess(() -> Component.literal("任务定义已重载"), true);
                                return 1;
                            }))
                    .then(Commands.literal("editor")
                            .requires(QuestCommands::admin)
                            .executes(ctx -> {
                                Path directory = FabricLoader.getInstance().getConfigDir().resolve(HabiTrainSurvive.MOD_ID);
                                try {
                                    Path file = QuestExport.writeEditor(ctx.getSource().getServer(), directory);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "已生成任务编辑器：" + file.toAbsolutePath()
                                                    + "\n包含每日 / 每周 / 章节任务编辑（章节支持新建、网格位置、前置依赖与隐藏）"
                                                    + "\n用浏览器打开、编辑并导出 quests.json，覆盖同目录文件后执行 /quest reload"), false);
                                    return 1;
                                } catch (Exception exception) {
                                    ctx.getSource().sendFailure(Component.literal("生成编辑器失败: " + exception.getMessage()));
                                    return 0;
                                }
                            }))
                    .then(Commands.literal("web")
                            .requires(QuestCommands::admin)
                            .executes(ctx -> webStart(ctx.getSource(), null))
                            .then(Commands.literal("start").executes(ctx -> webStart(ctx.getSource(), null)))
                            .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                                    .executes(ctx -> webStart(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "port"))))
                            .then(Commands.literal("stop").executes(ctx -> {
                                if (QuestWebEditor.active() == null) {
                                    ctx.getSource().sendFailure(Component.literal("任务在线编辑未在运行"));
                                    return 0;
                                }
                                QuestWebEditor.stop();
                                ctx.getSource().sendSuccess(() -> Component.literal("任务在线编辑已关闭"), true);
                                return 1;
                            }))
                            .then(Commands.literal("status").executes(ctx -> {
                                QuestWebEditor editor = QuestWebEditor.active();
                                if (editor == null) {
                                    ctx.getSource().sendSuccess(() -> Component.literal("任务在线编辑未运行；使用 /quest web 启动"), false);
                                    return 1;
                                }
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "任务在线编辑运行中：" + editor.url() + "（端口 " + editor.port() + "）"), false);
                                return 1;
                            })))
                    .then(Commands.literal("reset")
                            .requires(QuestCommands::admin)
                            .then(Commands.argument("player", EntityArgument.player())
                                    .executes(ctx -> {
                                        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                        QuestManager.get().resetPlayer(target.getUUID());
                                        ctx.getSource().sendSuccess(() -> Component.literal("已重置 " + target.getName().getString() + " 的每日/每周/章节任务进度"), true);
                                        return 1;
                                    })))
                    .then(Commands.literal("status")
                            .requires(QuestCommands::admin)
                            .executes(ctx -> {
                                QuestManager manager = QuestManager.get();
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "任务定义: " + (manager.catalog() == null ? "未加载" : manager.catalog().allIds().size() + " 个；"
                                                + "每日模板 " + manager.catalog().daily().size()
                                                + "，每周模板 " + manager.catalog().weekly().size()
                                                + "，章节 " + manager.catalog().chapters().size())
                                                + "；错误: " + (manager.catalogError().isBlank() ? "无" : manager.catalogError())), false);
                                return 1;
                            })));
        }
    }

    private static int webStart(CommandSourceStack source, Integer port) {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve(HabiTrainSurvive.MOD_ID);
        try {
            String url = QuestWebEditor.start(source.getServer(), directory, port, null);
            source.sendSuccess(() -> Component.literal(
                    "任务在线编辑已启动：" + url
                            + "\n可编辑每日、每周与章节任务（章节内任务支持坐标、前置依赖、隐藏与奖励）"
                            + "\n在浏览器打开该链接即可编辑并直接保存回 quests.json；保存会先校验，校验失败不会覆盖文件。"
                            + "\n关闭：/quest web stop；查看状态：/quest web status"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("启动任务在线编辑失败: " + exception.getMessage()));
            return 0;
        }
    }

    private static boolean admin(CommandSourceStack source) {
        return PermissionManager.get().hasPermission(source, "habi.admin", 2);
    }

    static List<String> aliases() { return List.of("quest", "quests", "mission"); }
}
