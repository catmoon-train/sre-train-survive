package io.wifi.habitrainsurvive.redeem;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class RedeemCommands {
    private static final String MODULE = "兑换码";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 玩家快捷兑换: /redeem <code>
        dispatcher.register(Commands.literal("redeem")
                .then(Commands.argument("code", StringArgumentType.word())
                        .executes(ctx -> {
                            RedeemManager.get().redeem(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "code"));
                            return 1;
                        })));

        // /cdk <code> 或 OP 管理子命令
        dispatcher.register(Commands.literal("cdk")
                .then(Commands.literal("use")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> {
                                    RedeemManager.get().redeem(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "code"));
                                    return 1;
                                })))
                .then(Commands.literal("create")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("code", StringArgumentType.word())
                                .then(Commands.argument("maxUses", IntegerArgumentType.integer(1, 100000))
                                        .then(Commands.argument("coins", DoubleArgumentType.doubleArg(0.0))
                                                .then(Commands.argument("durationHours", LongArgumentType.longArg(1, 87600))
                                                        .executes(ctx -> {
                                                            ServerPlayer admin = ctx.getSource().getPlayerOrException();
                                                            String code = StringArgumentType.getString(ctx, "code").toUpperCase();
                                                            int maxUses = IntegerArgumentType.getInteger(ctx, "maxUses");
                                                            double coins = DoubleArgumentType.getDouble(ctx, "coins");
                                                            long hours = LongArgumentType.getLong(ctx, "durationHours");
                                                            List<String> items = new ArrayList<>();
                                                            ItemStack held = admin.getMainHandItem();
                                                            if (!held.isEmpty()) {
                                                                items.add(ItemSerializer.serialize(held.copy(), admin.server.registryAccess()));
                                                            }
                                                            RedeemManager.get().putCode(new RedeemCode(code, maxUses, coins, items, "", hours));
                                                            MessageUtil.sendSuccess(admin, MODULE, "已创建兑换码 [" + code + "] (可用次数:" + maxUses + ", 金币:" + coins + ", 附带主手物品:" + items.size() + "件)");
                                                            return 1;
                                                        }))))))
                .then(Commands.literal("batch")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("prefix", StringArgumentType.word())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                        .then(Commands.argument("maxUsesEach", IntegerArgumentType.integer(1, 10000))
                                                .then(Commands.argument("coins", DoubleArgumentType.doubleArg(0.0))
                                                        .then(Commands.argument("durationHours", LongArgumentType.longArg(1, 87600))
                                                                .executes(ctx -> {
                                                                    ServerPlayer admin = ctx.getSource().getPlayerOrException();
                                                                    String prefix = StringArgumentType.getString(ctx, "prefix");
                                                                    int count = IntegerArgumentType.getInteger(ctx, "count");
                                                                    int maxUses = IntegerArgumentType.getInteger(ctx, "maxUsesEach");
                                                                    double coins = DoubleArgumentType.getDouble(ctx, "coins");
                                                                    long hours = LongArgumentType.getLong(ctx, "durationHours");
                                                                    List<String> items = new ArrayList<>();
                                                                    if (!admin.getMainHandItem().isEmpty()) {
                                                                        items.add(ItemSerializer.serialize(admin.getMainHandItem().copy(), admin.server.registryAccess()));
                                                                    }
                                                                    List<String> codes = RedeemManager.get().generateBatchCodes(prefix, count, maxUses, coins, items, "", hours);
                                                                    MessageUtil.sendSuccess(admin, MODULE, "成功批量生成 " + count + " 个卡密: " + String.join(", ", codes));
                                                                    return 1;
                                                                })))))))
                .then(Commands.literal("additem")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer admin = ctx.getSource().getPlayerOrException();
                                    RedeemCode rc = RedeemManager.get().getCode(StringArgumentType.getString(ctx, "code"));
                                    if (rc == null) {
                                        MessageUtil.sendError(admin, MODULE, "兑换码不存在。");
                                        return 0;
                                    }
                                    ItemStack held = admin.getMainHandItem();
                                    if (held.isEmpty()) {
                                        MessageUtil.sendError(admin, MODULE, "请主手持有要追加进该兑换码的奖品物品！");
                                        return 0;
                                    }
                                    rc.serializedItems.add(ItemSerializer.serialize(held.copy(), admin.server.registryAccess()));
                                    MessageUtil.sendSuccess(admin, MODULE, "已向兑换码 [" + rc.code + "] 追加物品 [" + held.getHoverName().getString() + " x" + held.getCount() + "]！");
                                    return 1;
                                })))
                .then(Commands.literal("setgroup")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("code", StringArgumentType.word())
                                .then(Commands.argument("group", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer admin = ctx.getSource().getPlayerOrException();
                                            RedeemCode rc = RedeemManager.get().getCode(StringArgumentType.getString(ctx, "code"));
                                            if (rc == null) {
                                                MessageUtil.sendError(admin, MODULE, "兑换码不存在。");
                                                return 0;
                                            }
                                            rc.grantGroup = StringArgumentType.getString(ctx, "group");
                                            MessageUtil.sendSuccess(admin, MODULE, "已设置兑换码 [" + rc.code + "] 赠送权限组: " + rc.grantGroup);
                                            return 1;
                                        }))))
                .then(Commands.literal("list")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal("=== 服务器兑换码列表 ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
                            RedeemManager.get().getCodes().values().forEach(rc -> ctx.getSource().sendSuccess(
                                    () -> Component.literal("- [" + rc.code + "] ")
                                            .withStyle(ChatFormatting.AQUA)
                                            .append(Component.literal(String.format("剩余次数:%d/%d 金币:%s 物品:%d组 权限组:%s",
                                                    rc.getRemainingUses(), rc.maxUses, EconomyManager.get().format(rc.rewardCoins),
                                                    rc.serializedItems.size(), rc.grantGroup.isEmpty() ? "无" : rc.grantGroup)).withStyle(ChatFormatting.WHITE)),
                                    false
                            ));
                            return 1;
                        }))
                .then(Commands.literal("delete")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> {
                                    String c = StringArgumentType.getString(ctx, "code");
                                    RedeemManager.get().removeCode(c);
                                    ctx.getSource().sendSuccess(() -> Component.literal("已删除兑换码 [" + c.toUpperCase() + "]"), true);
                                    return 1;
                                })))
                .then(Commands.argument("code", StringArgumentType.word())
                        .executes(ctx -> {
                            RedeemManager.get().redeem(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "code"));
                            return 1;
                        })));
    }
}
