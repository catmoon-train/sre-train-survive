package io.wifi.habitrainsurvive.economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

public class EconomyCommands {
    private static final String MODULE = "经济";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /balance & /money & /bal
        for (String alias : new String[]{"balance", "bal", "money"}) {
            dispatcher.register(Commands.literal(alias)
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        EconomyAccount acc = EconomyManager.get().getOrCreateAccount(player);
                        MessageUtil.sendInfo(player, MODULE, "您的当前余额为: " + EconomyManager.get().format(acc.balance));
                        return 1;
                    })
                    .then(Commands.argument("target", StringArgumentType.word())
                            .executes(ctx -> {
                                String targetName = StringArgumentType.getString(ctx, "target");
                                Optional<EconomyAccount> opt = EconomyManager.get().findByName(targetName);
                                if (opt.isEmpty()) {
                                    ctx.getSource().sendFailure(Component.literal("未找到玩家 " + targetName + " 的经济账户。"));
                                    return 0;
                                }
                                ctx.getSource().sendSuccess(() -> MessageUtil.prefix(MODULE, ChatFormatting.GOLD)
                                        .append(Component.literal("玩家 " + opt.get().name + " 的余额为: " + EconomyManager.get().format(opt.get().balance)).withStyle(ChatFormatting.YELLOW)), false);
                                return 1;
                            })));
        }

        // /pay <player> <amount>
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                    ServerPlayer receiver = EntityArgument.getPlayer(ctx, "target");
                                    double amount = Math.round(DoubleArgumentType.getDouble(ctx, "amount") * 100.0) / 100.0;
                                    if (sender.getUUID().equals(receiver.getUUID())) {
                                        MessageUtil.sendError(sender, MODULE, "您不能给自己转账！");
                                        return 0;
                                    }
                                    EconomyManager.get().getOrCreateAccount(sender);
                                    EconomyManager.get().getOrCreateAccount(receiver);
                                    if (!EconomyManager.get().transfer(sender.getUUID(), receiver.getUUID(), amount, "Player Pay")) {
                                        MessageUtil.sendError(sender, MODULE, "余额不足，无法支付 " + EconomyManager.get().format(amount));
                                        return 0;
                                    }
                                    MessageUtil.sendSuccess(sender, MODULE, "已成功向 " + receiver.getName().getString() + " 转账 " + EconomyManager.get().format(amount));
                                    MessageUtil.sendSuccess(receiver, MODULE, "收到来自 " + sender.getName().getString() + " 的转账 " + EconomyManager.get().format(amount));
                                    MessageUtil.playDing(receiver);
                                    return 1;
                                }))));

        // /baltop opens the visual ranking; /baltop chat [page] keeps the text output for console.
        dispatcher.register(Commands.literal("baltop")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) return showBaltop(ctx.getSource(), 1);
                    EconomyLeaderboardGui.open(player, 1);
                    return 1;
                })
                .then(Commands.literal("chat")
                        .executes(ctx -> showBaltop(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> showBaltop(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayer();
                            if (player == null) return showBaltop(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page"));
                            EconomyLeaderboardGui.open(player, IntegerArgumentType.getInteger(ctx, "page"));
                            return 1;
                        })));

        // /eco <give|take|set|reset> <player> <amount> (OP)
        dispatcher.register(Commands.literal("eco")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("give")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            double amount = DoubleArgumentType.getDouble(ctx, "amount");
                                            EconomyManager.get().getOrCreateAccount(target);
                                            EconomyManager.get().deposit(target.getUUID(), amount, "Admin Give");
                                            ctx.getSource().sendSuccess(() -> MessageUtil.prefix(MODULE, ChatFormatting.GREEN)
                                                    .append("已给予 " + target.getName().getString() + " " + EconomyManager.get().format(amount)), true);
                                            MessageUtil.sendInfo(target, MODULE, "管理员为您增加了 " + EconomyManager.get().format(amount));
                                            return 1;
                                        }))))
                .then(Commands.literal("take")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            double amount = DoubleArgumentType.getDouble(ctx, "amount");
                                            EconomyManager.get().getOrCreateAccount(target);
                                            if (!EconomyManager.get().withdraw(target.getUUID(), amount, "Admin Take")) {
                                                ctx.getSource().sendFailure(Component.literal("目标玩家余额不足。"));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> MessageUtil.prefix(MODULE, ChatFormatting.GREEN)
                                                    .append("已扣除 " + target.getName().getString() + " " + EconomyManager.get().format(amount)), true);
                                            MessageUtil.sendInfo(target, MODULE, "管理员扣除了您 " + EconomyManager.get().format(amount));
                                            return 1;
                                        }))))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            double amount = DoubleArgumentType.getDouble(ctx, "amount");
                                            EconomyManager.get().getOrCreateAccount(target);
                                            EconomyManager.get().setBalance(target.getUUID(), amount, "Admin Set");
                                            ctx.getSource().sendSuccess(() -> MessageUtil.prefix(MODULE, ChatFormatting.GREEN)
                                                    .append("已将 " + target.getName().getString() + " 的余额设为 " + EconomyManager.get().format(amount)), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    EconomyManager.get().getOrCreateAccount(target);
                                    double def = EconomyManager.get().getStartingBalance();
                                    EconomyManager.get().setBalance(target.getUUID(), def, "Admin Reset");
                                    ctx.getSource().sendSuccess(() -> MessageUtil.prefix(MODULE, ChatFormatting.GREEN)
                                            .append("已重置 " + target.getName().getString() + " 的余额为 " + EconomyManager.get().format(def)), true);
                                    return 1;
                                }))));
    }

    private static int showBaltop(CommandSourceStack source, int page) {
        List<EconomyAccount> all = EconomyManager.get().getTopAccounts(100);
        int pageSize = 10;
        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) pageSize));
        int currentPage = Math.min(page, totalPages);
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, all.size());

        source.sendSuccess(() -> Component.literal("=== 服务器财富排行榜 (第 " + currentPage + "/" + totalPages + " 页) ===")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        for (int i = start; i < end; i++) {
            EconomyAccount acc = all.get(i);
            int rank = i + 1;
            source.sendSuccess(() -> Component.literal("#" + rank + " ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(acc.name + " - ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(EconomyManager.get().format(acc.balance)).withStyle(ChatFormatting.GREEN)), false);
        }
        return 1;
    }
}
