package io.wifi.habitrainsurvive.plot;

import com.mojang.brigadier.CommandDispatcher;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import io.wifi.habitrainsurvive.world.WorldManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class PlotCommands {
    private PlotCommands() { }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = dispatcher.register(Commands.literal("plot")
                .executes(ctx -> { PlotGui.open(ctx.getSource().getPlayerOrException()); return 1; })
                .then(Commands.literal("buy").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    try { PlotGui.confirmBuy(player, PlotManager.get().nextAvailable()); }
                    catch (IllegalArgumentException e) { MessageUtil.sendError(player, "地皮", e.getMessage()); return 0; }
                    return 1;
                }))
                .then(Commands.literal("mine").executes(ctx -> { PlotGui.list(ctx.getSource().getPlayerOrException(), false); return 1; }))
                .then(Commands.literal("home").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException(); var plots = PlotManager.get().owned(player.getUUID());
                    if (plots.isEmpty()) { PlotGui.open(player); return 0; }
                    PlotManager.get().teleport(player, plots.getFirst()); return 1;
                }))
                .then(Commands.literal("world").executes(ctx -> WorldManager.get().teleport(ctx.getSource().getPlayerOrException(), PlotGrid.WORLD) ? 1 : 0))
                .then(Commands.literal("admin").requires(source -> PermissionManager.get().hasPermission(source, "habi.plot.admin", 2))
                        .executes(ctx -> { PlotGui.openAdmin(ctx.getSource().getPlayerOrException()); return 1; })));
        dispatcher.register(Commands.literal("plots").redirect(root));
    }
}
