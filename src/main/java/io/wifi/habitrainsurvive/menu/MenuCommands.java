package io.wifi.habitrainsurvive.menu;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.io.IOException;

public final class MenuCommands {
    private MenuCommands() { }
    private static RequiredArgumentBuilder<CommandSourceStack, String> idArgument() {
        return Commands.argument("id", StringArgumentType.word()).suggests((ctx, builder) -> {
            var player = ctx.getSource().getPlayer();
            return SharedSuggestionProvider.suggest(player == null ? java.util.List.of() : MenuManager.get().menuIds(player), builder);
        });
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String alias : new String[]{"menu", "cd"}) {
            dispatcher.register(Commands.literal(alias)
                    .executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        MenuTextInput.cancel(player, false); MenuManager.get().open(player, null); return 1;
                    })
                    .then(Commands.literal("edit").requires(source -> source.hasPermission(2))
                            .executes(ctx -> { MenuEditorGui.open(ctx.getSource().getPlayerOrException()); return 1; })
                            .then(idArgument().executes(ctx -> {
                                MenuEditorGui.open(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id")); return 1;
                            })))
                    .then(Commands.literal("cancel").requires(source -> source.hasPermission(2)).executes(ctx -> {
                        MenuTextInput.cancel(ctx.getSource().getPlayerOrException(), true); return 1;
                    }))
                    .then(Commands.literal("list").executes(ctx -> { MenuManager.get().openSelector(ctx.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("open").then(idArgument().executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException(); MenuTextInput.cancel(player, false);
                        MenuManager.get().open(player, StringArgumentType.getString(ctx, "id")); return 1;
                    })))
                    .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(ctx -> {
                        try {
                            MenuManager.get().reload();
                            ctx.getSource().sendSuccess(() -> Component.literal("菜单配置已重载；旧编辑草稿需放弃并重新打开，避免覆盖新配置。"), true); return 1;
                        } catch (IOException | IllegalArgumentException e) {
                            ctx.getSource().sendFailure(Component.literal("菜单重载失败，保留有效配置: " + e.getMessage())); return 0;
                        }
                    }))
                    .then(idArgument().executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException(); MenuTextInput.cancel(player, false);
                        MenuManager.get().open(player, StringArgumentType.getString(ctx, "id")); return 1;
                    })));
        }
        dispatcher.register(Commands.literal("menus").executes(ctx -> { MenuManager.get().openSelector(ctx.getSource().getPlayerOrException()); return 1; }));
        dispatcher.register(Commands.literal("menueditor").requires(source -> source.hasPermission(2))
                .executes(ctx -> { MenuEditorGui.open(ctx.getSource().getPlayerOrException()); return 1; }));
    }
}
