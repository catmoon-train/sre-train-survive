package io.wifi.habitrainsurvive.lottery;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public class LotteryCommands {
    private static final String MODULE = "幸运抽奖";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"lottery", "crate", "gacha"}) {
            dispatcher.register(Commands.literal(root)
                    .executes(ctx -> {
                        LotteryGui.openPoolSelector(ctx.getSource().getPlayerOrException());
                        return 1;
                    })
                    .then(Commands.literal("admin")
                            .requires(src -> src.hasPermission(2))
                            .executes(ctx -> {
                                LotteryAdminGui.open(ctx.getSource().getPlayerOrException());
                                return 1;
                            }))
                    .then(Commands.literal("draw")
                            .then(Commands.argument("pool", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        CratePool pool = LotteryManager.get().getPool(StringArgumentType.getString(ctx, "pool"));
                                        LotteryManager.get().performDraw(p, pool, 1);
                                        return 1;
                                    })
                                    .then(Commands.argument("times", IntegerArgumentType.integer(1, 10))
                                            .executes(ctx -> {
                                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                CratePool pool = LotteryManager.get().getPool(StringArgumentType.getString(ctx, "pool"));
                                                LotteryManager.get().performDraw(p, pool, IntegerArgumentType.getInteger(ctx, "times"));
                                                return 1;
                                            }))))
                    .then(Commands.literal("givekey")
                            .requires(src -> src.hasPermission(2))
                            .then(Commands.argument("target", EntityArgument.player())
                                    .then(Commands.argument("pool", StringArgumentType.word())
                                            .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                    .executes(ctx -> {
                                                        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                        CratePool pool = LotteryManager.get().getPool(StringArgumentType.getString(ctx, "pool"));
                                                        int count = IntegerArgumentType.getInteger(ctx, "count");
                                                        if (pool == null) {
                                                            MessageUtil.sendError(ctx.getSource().getPlayerOrException(), MODULE, "未找到该奖池 (可选: common, rare, legendary)");
                                                            return 0;
                                                        }
                                                        ItemStack key = LotteryManager.createCrateKeyItem(pool, count);
                                                        if (!target.getInventory().add(key)) {
                                                            target.drop(key, false);
                                                        }
                                                        MessageUtil.sendSuccess(target, MODULE, "收到管理员发放的 " + count + " 把 [" + pool.displayName + "§a] 祈愿钥匙！");
                                                        return 1;
                                                    })))))
                    .then(Commands.literal("addprize")
                            .requires(src -> src.hasPermission(2))
                            .then(Commands.argument("pool", StringArgumentType.word())
                                    .then(Commands.argument("weight", IntegerArgumentType.integer(1, 10000))
                                            .then(Commands.argument("bonusCoins", DoubleArgumentType.doubleArg(0.0))
                                                    .then(Commands.argument("rareBroadcast", BoolArgumentType.bool())
                                                            .executes(ctx -> {
                                                                ServerPlayer admin = ctx.getSource().getPlayerOrException();
                                                                CratePool pool = LotteryManager.get().getPool(StringArgumentType.getString(ctx, "pool"));
                                                                if (pool == null) {
                                                                    MessageUtil.sendError(admin, MODULE, "未找到奖池。");
                                                                    return 0;
                                                                }
                                                                ItemStack held = admin.getMainHandItem();
                                                                if (held.isEmpty()) {
                                                                    MessageUtil.sendError(admin, MODULE, "请主手持有要加入奖池的物品！");
                                                                    return 0;
                                                                }
                                                                int weight = IntegerArgumentType.getInteger(ctx, "weight");
                                                                double bonus = DoubleArgumentType.getDouble(ctx, "bonusCoins");
                                                                boolean rare = BoolArgumentType.getBool(ctx, "rareBroadcast");
                                                                pool.prizes.add(new CratePrize(
                                                                        ItemSerializer.serialize(held.copy(), admin.server.registryAccess()),
                                                                        held.getHoverName().getString(),
                                                                        held.getCount(),
                                                                        bonus,
                                                                        weight,
                                                                        rare
                                                                ));
                                                                LotteryManager.get().saveChanges();
                                                                MessageUtil.sendSuccess(admin, MODULE, "成功将 [" + held.getHoverName().getString() + " x" + held.getCount() + "] 添加至奖池 [" + pool.displayName + "§a]！");
                                                                return 1;
                                                            })))))));
        }
    }
}
