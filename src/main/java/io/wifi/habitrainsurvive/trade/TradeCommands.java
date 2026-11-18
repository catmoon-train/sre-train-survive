package io.wifi.habitrainsurvive.trade;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;

public class TradeCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("trade")
                .then(Commands.literal("accept")
                        .executes(ctx -> {
                            TradeManager.get().acceptTrade(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("deny")
                        .executes(ctx -> {
                            TradeManager.get().denyTrade(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> {
                            TradeManager.get().requestTrade(
                                    ctx.getSource().getPlayerOrException(),
                                    EntityArgument.getPlayer(ctx, "target")
                            );
                            return 1;
                        })));
    }
}
