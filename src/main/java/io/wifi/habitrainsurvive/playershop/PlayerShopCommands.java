package io.wifi.habitrainsurvive.playershop;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class PlayerShopCommands {
    private PlayerShopCommands() { }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : new String[]{"playershop", "pshop"}) {
            dispatcher.register(Commands.literal(name)
                    .executes(ctx -> { PlayerShopGui.open(ctx.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.literal("mine").executes(ctx -> { PlayerShopGui.openMine(ctx.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("admin").requires(source -> source.hasPermission(2))
                            .executes(ctx -> { PlayerShopGui.openAdmin(ctx.getSource().getPlayerOrException()); return 1; })));
        }
    }
}
