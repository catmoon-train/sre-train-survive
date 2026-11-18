package io.wifi.habitrainsurvive.redpacket;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public final class RedPacketCommands {
    private RedPacketCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("redpacket")
                .executes(context -> { RedPacketGui.open(context.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("gui").executes(context -> {
                    RedPacketGui.open(context.getSource().getPlayerOrException()); return 1;
                }))
                .then(Commands.literal("list").executes(context -> {
                    RedPacketGui.open(context.getSource().getPlayerOrException()); return 1;
                }))
                .then(Commands.literal("send")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01, 1_000_000_000.0))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> send(context, "恭喜发财，大吉大利！"))
                                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                                .executes(context -> send(context,
                                                        StringArgumentType.getString(context, "message")))))))
                .then(Commands.literal("claim")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    RedPacketManager.get().claim(player, StringArgumentType.getString(context, "id"));
                                    return 1;
                                })))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    RedPacketManager.get().cancel(player, StringArgumentType.getString(context, "id"));
                                    return 1;
                                })))
                .then(Commands.literal("help").executes(context -> {
                    MessageUtil.sendInfo(context.getSource().getPlayerOrException(), "拼手气红包",
                            "发红包: /redpacket send <总额> <份数> [留言]；抢红包: /redpacket claim <编号>；红包列表: /redpacket");
                    return 1;
                })));
    }

    private static int send(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, String message)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        boolean sent = RedPacketManager.get().send(player,
                DoubleArgumentType.getDouble(context, "amount"),
                IntegerArgumentType.getInteger(context, "count"), message);
        return sent ? 1 : 0;
    }
}
