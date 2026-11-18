package io.wifi.habitrainsurvive.grave;

import com.mojang.brigadier.CommandDispatcher;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public class GraveCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"grave", "graves", "backdeath"}) {
            dispatcher.register(Commands.literal(root)
                    .executes(ctx -> {
                        GraveManager.get().openGui(ctx.getSource().getPlayerOrException());
                        return 1;
                    })
                    .then(Commands.literal("toggle")
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                boolean next = GraveManager.get().toggleInsurance(p.getUUID());
                                MessageUtil.sendSuccess(p, "死亡墓碑", "自动死亡遗物保护已" + (next ? "开启" : "关闭"));
                                return 1;
                            })));
        }
    }
}
