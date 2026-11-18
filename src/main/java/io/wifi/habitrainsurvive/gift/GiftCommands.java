package io.wifi.habitrainsurvive.gift;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public class GiftCommands {
    private static final String MODULE = "礼物中心";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kits")
                .executes(ctx -> {
                    GiftGui.openKitCenter(ctx.getSource().getPlayerOrException());
                    return 1;
                }));

        dispatcher.register(Commands.literal("gift")
                .executes(ctx -> {
                    GiftGui.openGiftBox(ctx.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.literal("box")
                        .executes(ctx -> {
                            GiftGui.openGiftBox(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("kits")
                        .executes(ctx -> {
                            GiftGui.openKitCenter(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("send")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> sendHandGift(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "target"), "祝你游戏愉快！"))
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> sendHandGift(
                                                ctx.getSource().getPlayerOrException(),
                                                EntityArgument.getPlayer(ctx, "target"),
                                                StringArgumentType.getString(ctx, "message")
                                        )))))
                .then(Commands.literal("money")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(1.0))
                                        .executes(ctx -> sendMoneyGift(
                                                ctx.getSource().getPlayerOrException(),
                                                EntityArgument.getPlayer(ctx, "target"),
                                                DoubleArgumentType.getDouble(ctx, "amount"),
                                                "恭贺佳节，大吉大利！"
                                        ))
                                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                                .executes(ctx -> sendMoneyGift(
                                                        ctx.getSource().getPlayerOrException(),
                                                        EntityArgument.getPlayer(ctx, "target"),
                                                        DoubleArgumentType.getDouble(ctx, "amount"),
                                                        StringArgumentType.getString(ctx, "message")
                                                ))))))
                .then(Commands.literal("dropall")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("coins", DoubleArgumentType.doubleArg(0.0))
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer admin = ctx.getSource().getPlayerOrException();
                                            double coins = DoubleArgumentType.getDouble(ctx, "coins");
                                            String msg = StringArgumentType.getString(ctx, "message");
                                            ItemStack held = admin.getMainHandItem().copy();
                                            int sent = 0;
                                            for (ServerPlayer online : admin.server.getPlayerList().getPlayers()) {
                                                GiftManager.get().sendGift(admin.server, admin.getUUID(), "全服福利空投", online.getUUID(), held.copy(), coins, msg);
                                                sent++;
                                            }
                                            admin.server.getPlayerList().broadcastSystemMessage(
                                                    MessageUtil.prefix("全服空投", ChatFormatting.GOLD)
                                                            .append(Component.literal("管理员向全服 " + sent + " 名在线玩家派发了空投大礼！输入 /gift box 立即拆开！").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)),
                                                    false
                                            );
                                            return 1;
                                        })))));
    }

    private static int sendHandGift(ServerPlayer sender, ServerPlayer target, String message) {
        if (sender.getUUID().equals(target.getUUID())) {
            MessageUtil.sendError(sender, MODULE, "不能给自己赠送礼物！");
            return 0;
        }
        ItemStack held = sender.getMainHandItem();
        if (held.isEmpty()) {
            MessageUtil.sendError(sender, MODULE, "请主手拿着要作为礼物赠送的物品！");
            return 0;
        }
        ItemStack copy = held.copy();
        sender.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GiftManager.get().sendGift(sender.server, sender.getUUID(), sender.getName().getString(), target.getUUID(), copy, 0.0, message);
        MessageUtil.sendSuccess(sender, MODULE, "已将 [" + copy.getHoverName().getString() + " x" + copy.getCount() + "] 包装成礼盒送给 " + target.getName().getString() + "！");
        return 1;
    }

    private static int sendMoneyGift(ServerPlayer sender, ServerPlayer target, double amount, String message) {
        if (sender.getUUID().equals(target.getUUID())) {
            MessageUtil.sendError(sender, MODULE, "不能给自己发红包！");
            return 0;
        }
        if (!EconomyManager.get().withdraw(sender.getUUID(), amount, "Send Gift RedPacket to " + target.getName().getString())) {
            MessageUtil.sendError(sender, MODULE, "您的金币不足以发送该红包！");
            return 0;
        }
        GiftManager.get().sendGift(sender.server, sender.getUUID(), sender.getName().getString(), target.getUUID(), ItemStack.EMPTY, amount, message);
        MessageUtil.sendSuccess(sender, MODULE, "已向 " + target.getName().getString() + " 发送了 " + EconomyManager.get().format(amount) + " 礼金红包！");
        return 1;
    }
}
