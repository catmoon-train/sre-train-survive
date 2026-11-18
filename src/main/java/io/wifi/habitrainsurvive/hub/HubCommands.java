package io.wifi.habitrainsurvive.hub;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.security.Confirmations;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.world.SafeTeleportService;
import io.wifi.habitrainsurvive.world.WorldManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import java.io.IOException;

public final class HubCommands {
    private HubCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String alias : new String[]{"hub", "lobby"}) dispatcher.register(Commands.literal(alias)
                .requires(s -> PermissionManager.get().hasPermission(s, "habi.cmi.spawn", 0))
                .executes(ctx -> { HubManager.get().teleport(ctx.getSource().getPlayerOrException()); return 1; }));
        dispatcher.register(Commands.literal("hubadmin").requires(s -> PermissionManager.get().hasPermission(s, "habi.hub.admin", 2))
                .then(Commands.literal("setoverworldspawn").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!player.serverLevel().dimension().equals(Level.OVERWORLD)
                            || !SafeTeleportService.safe(player.serverLevel(), player.blockPosition())
                            || !player.serverLevel().getWorldBorder().isWithinBounds(player.getBoundingBox())
                            || !player.serverLevel().noCollision(player, player.getBoundingBox())) {
                        ctx.getSource().sendFailure(Component.literal("请站在主世界有安全支撑、无碰撞的落地点再执行此指令")); return 0;
                    }
                    GlobalPosInfo point = GlobalPosInfo.fromPlayer(player);
                    try {
                        WorldManager.get().update(config -> config.worlds.stream()
                                .filter(world -> world.id.equals("minecraft:overworld")).findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("主世界未登记")).spawn = point);
                        ctx.getSource().sendSuccess(() -> Component.literal("大厅前往主世界的落地点已设置：" + point.toShortString()
                                + "（已保存朝向，立即生效）"), true); return 1;
                    } catch (IOException | IllegalArgumentException e) {
                        ctx.getSource().sendFailure(Component.literal("保存落地点失败：" + e.getMessage())); return 0;
                    }
                }))
                .then(Commands.literal("rebuild").executes(ctx -> {
                    String token = Confirmations.issue(ctx.getSource(), "hub-rebuild");
                    ctx.getSource().sendSuccess(() -> Component.literal("§c这会覆盖主城初始广场范围！30秒内确认：/hubadmin rebuild confirm " + token), false); return 1;
                }).then(Commands.literal("confirm").then(Commands.argument("token", StringArgumentType.word()).executes(ctx -> {
                    if (!Confirmations.consume(ctx.getSource(), "hub-rebuild", StringArgumentType.getString(ctx, "token"))) {
                        ctx.getSource().sendFailure(Component.literal("确认无效或已过期")); return 0;
                    }
                    try { HubManager.get().rebuild(); return 1; } catch (IOException e) { ctx.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
                })))));
    }
}
