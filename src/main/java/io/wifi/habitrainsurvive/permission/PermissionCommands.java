package io.wifi.habitrainsurvive.permission;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class PermissionCommands {
    private static final String MODULE = "权限组";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("perm")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("groups")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal("=== 服务器权限组列表 ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
                            PermissionManager.get().getGroups().values().forEach(g -> ctx.getSource().sendSuccess(
                                    () -> Component.literal("- " + g.name + " (" + g.prefix + "§r) ")
                                            .withStyle(ChatFormatting.AQUA)
                                            .append(Component.literal(String.format("家上限:%d 领地上限:%d 拍卖上限:%d 传送读条:%ds RTP冷却:%ds",
                                                    g.maxHomes, g.maxClaims, g.maxAuctionListings, g.teleportWarmupSeconds, g.rtpCooldownSeconds)).withStyle(ChatFormatting.WHITE)),
                                    false
                            ));
                            return 1;
                        }))
                .then(Commands.literal("setgroup")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            String group = StringArgumentType.getString(ctx, "group");
                                            if (!PermissionManager.get().setPlayerGroup(target.getUUID(), group)) {
                                                ctx.getSource().sendFailure(Component.literal("未找到权限组: " + group + " (可用: default, vip, svip, admin)"));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal("已将玩家 " + target.getName().getString() + " 的权限组设置为 " + group), true);
                                            MessageUtil.sendSuccess(target, MODULE, "您的会员权限组已变更为: " + group);
                                            return 1;
                                        }))))
                .then(Commands.literal("addperm")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("node", StringArgumentType.string())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            String node = StringArgumentType.getString(ctx, "node");
                                            PermissionManager.get().addPlayerPerm(target.getUUID(), node);
                                            ctx.getSource().sendSuccess(() -> Component.literal("已为 " + target.getName().getString() + " 授予权限节点: " + node), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("delperm")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("node", StringArgumentType.string())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            String node = StringArgumentType.getString(ctx, "node");
                                            PermissionManager.get().removePlayerPerm(target.getUUID(), node);
                                            ctx.getSource().sendSuccess(() -> Component.literal("已移除 " + target.getName().getString() + " 的权限节点: " + node), true);
                                            return 1;
                                        })))));
    }
}
