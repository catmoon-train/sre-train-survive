package io.wifi.habitrainsurvive.title;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

public class TitleCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"title", "titles"}) {
            dispatcher.register(Commands.literal(root)
                    .executes(ctx -> {
                        TitleManager.get().openGui(ctx.getSource().getPlayerOrException());
                        return 1;
                    })
                    .then(Commands.literal("grant")
                            .requires(src -> src.hasPermission(2))
                            .then(Commands.argument("target", EntityArgument.player())
                                    .then(Commands.argument("titleId", StringArgumentType.word())
                                            .executes(ctx -> {
                                                ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                String id = StringArgumentType.getString(ctx, "titleId").toLowerCase();
                                                if (!TitleManager.get().getTitles().containsKey(id)) {
                                                    MessageUtil.sendError(ctx.getSource().getPlayerOrException(), "称号中心", "称号ID不存在。");
                                                    return 0;
                                                }
                                                TitleManager.get().getUnlocked(target.getUUID()).add(id);
                                                MessageUtil.sendSuccess(target, "称号中心", "管理员为您授予了新头衔 [" + id + "]，输入 /titles 即可佩戴！");
                                                return 1;
                                            }))))
                    .then(Commands.literal("create")
                            .requires(src -> src.hasPermission(2))
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.0))
                                            .then(Commands.argument("display", StringArgumentType.greedyString())
                                                    .executes(ctx -> {
                                                        String id = StringArgumentType.getString(ctx, "id").toLowerCase();
                                                        double price = DoubleArgumentType.getDouble(ctx, "price");
                                                        String display = StringArgumentType.getString(ctx, "display").replace('&', '§');
                                                        TitleManager.get().getTitles().put(id, new TitleManager.TitleDef(id, display, price, "服务器专属定制头衔"));
                                                        MessageUtil.sendSuccess(ctx.getSource().getPlayerOrException(), "称号中心", "已创建新头衔: " + display);
                                                        return 1;
                                                    }))))));
        }
    }
}
