package io.wifi.habitrainsurvive.chestshop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.UUID;

public class ChestShopCommands {
    private static final String MODULE = "箱子商店";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"qs", "chestshop", "cshop"}) {
            dispatcher.register(Commands.literal(root)
                    .executes(ctx -> { ChestShopGui.openBrowser(ctx.getSource().getPlayerOrException(), 1); return 1; })
                    .then(Commands.literal("browse").executes(ctx -> { ChestShopGui.openBrowser(ctx.getSource().getPlayerOrException(), 1); return 1; }))
                    .then(Commands.literal("mine").executes(ctx -> { ChestShopGui.openMyShops(ctx.getSource().getPlayerOrException()); return 1; }))
                    .then(Commands.literal("create")
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                BlockPos pos = getLookedAtBlock(p);
                                if (pos != null) ChestShopGui.confirmCreate(p, pos);
                                return 1;
                            })
                            .then(Commands.argument("buyPrice", DoubleArgumentType.doubleArg(0.0))
                                    .executes(ctx -> createShop(
                                            ctx.getSource().getPlayerOrException(),
                                            DoubleArgumentType.getDouble(ctx, "buyPrice"),
                                            0.0
                                    ))
                                    .then(Commands.argument("sellPrice", DoubleArgumentType.doubleArg(0.0))
                                            .executes(ctx -> createShop(
                                                    ctx.getSource().getPlayerOrException(),
                                                    DoubleArgumentType.getDouble(ctx, "buyPrice"),
                                                    DoubleArgumentType.getDouble(ctx, "sellPrice")
                                            )))))
                    .then(Commands.literal("remove")
                            .executes(ctx -> removeShop(ctx.getSource().getPlayerOrException())))
                    .then(Commands.literal("price")
                            .then(Commands.argument("buyPrice", DoubleArgumentType.doubleArg(0.0))
                                    .then(Commands.argument("sellPrice", DoubleArgumentType.doubleArg(0.0))
                                            .executes(ctx -> updatePrice(
                                                    ctx.getSource().getPlayerOrException(),
                                                    DoubleArgumentType.getDouble(ctx, "buyPrice"),
                                                    DoubleArgumentType.getDouble(ctx, "sellPrice")
                                            )))))
                    .then(Commands.literal("buy")
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 2304))
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        ChestShop shop = resolveTargetOrSelectedShop(p);
                                        if (shop == null) {
                                            MessageUtil.sendError(p, MODULE, "请先准星对准或左键点击一个箱子商店！");
                                            return 0;
                                        }
                                        ChestShopManager.get().buyFromShop(p, shop, IntegerArgumentType.getInteger(ctx, "amount"));
                                        return 1;
                                    })))
                    .then(Commands.literal("sell")
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 2304))
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        ChestShop shop = resolveTargetOrSelectedShop(p);
                                        if (shop == null) {
                                            MessageUtil.sendError(p, MODULE, "请先准星对准或左键点击一个箱子商店！");
                                            return 0;
                                        }
                                        ChestShopManager.get().sellToShop(p, shop, IntegerArgumentType.getInteger(ctx, "amount"));
                                        return 1;
                                    })))
                    .then(Commands.literal("info")
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                ChestShop shop = resolveTargetOrSelectedShop(p);
                                if (shop == null) {
                                    MessageUtil.sendError(p, MODULE, "请准星看向或点击一个箱子商店。");
                                    return 0;
                                }
                                ChestShopListener.showShopCard(p, shop);
                                return 1;
                            }))
                    .then(Commands.literal("find")
                            .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        String kw = StringArgumentType.getString(ctx, "keyword");
                                        List<ChestShop> list = ChestShopManager.get().searchShops(kw);
                                        if (list.isEmpty()) {
                                            MessageUtil.sendInfo(p, MODULE, "未找到包含关键词 [" + kw + "] 的箱子商店。");
                                            return 1;
                                        }
                                        p.sendSystemMessage(Component.literal("=== 商店搜索结果 (" + list.size() + ") ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                                        for (ChestShop s : list) {
                                            p.sendSystemMessage(Component.literal("- [" + s.itemDisplayName + "] ")
                                                    .withStyle(ChatFormatting.AQUA)
                                                    .append(Component.literal(String.format("店主:%s 坐标:(%d,%d,%d) 售:%s 收:%s",
                                                            s.ownerName, s.x, s.y, s.z,
                                                            EconomyManager.get().format(s.buyPrice),
                                                            EconomyManager.get().format(s.sellPrice))).withStyle(ChatFormatting.WHITE)));
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("staff")
                            .then(Commands.literal("add")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> {
                                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                ChestShop shop = resolveTargetOrSelectedShop(p);
                                                if (shop == null || !shop.ownerUuid.equals(p.getUUID())) {
                                                    MessageUtil.sendError(p, MODULE, "请对准您自己的箱子商店操作。");
                                                    return 0;
                                                }
                                                shop.staff.add(target.getUUID());
                                                ChestShopManager.get().saveChanges();
                                                MessageUtil.sendSuccess(p, MODULE, "已添加 " + target.getName().getString() + " 为该商店店员（可打开容器补货）。");
                                                return 1;
                                            })))
                            .then(Commands.literal("remove")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> {
                                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                ChestShop shop = resolveTargetOrSelectedShop(p);
                                                if (shop == null || !shop.ownerUuid.equals(p.getUUID())) {
                                                    MessageUtil.sendError(p, MODULE, "请对准您自己的箱子商店操作。");
                                                    return 0;
                                                }
                                                shop.staff.remove(target.getUUID());
                                                ChestShopManager.get().saveChanges();
                                                MessageUtil.sendSuccess(p, MODULE, "已移除店员 " + target.getName().getString());
                                                return 1;
                                            }))))
                    .then(Commands.literal("admin")
                            .requires(src -> src.hasPermission(2))
                            .executes(ctx -> {
                                ChestShopGui.openAdmin(ctx.getSource().getPlayerOrException());
                                return 1;
                            })));
        }
    }

    private static BlockPos getLookedAtBlock(ServerPlayer player) {
        HitResult hit = player.pick(5.0, 0.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
            return bhr.getBlockPos();
        }
        return null;
    }

    private static ChestShop resolveTargetOrSelectedShop(ServerPlayer player) {
        BlockPos pos = getLookedAtBlock(player);
        if (pos != null) {
            ChestShop looked = ChestShopManager.get().getShopAt(player.serverLevel().dimension().location().toString(), pos);
            if (looked != null) {
                ChestShopManager.get().setLastSelectedShop(player.getUUID(), looked.getPosKey());
                return looked;
            }
        }
        return ChestShopManager.get().getLastSelectedShop(player.getUUID());
    }

    private static int createShop(ServerPlayer player, double buyPrice, double sellPrice) {
        BlockPos pos = getLookedAtBlock(player);
        if (pos == null || player.getMainHandItem().isEmpty()) {
            MessageUtil.sendError(player, MODULE, "请手持样品看向 5 格内的单箱、木桶或潜影盒。木锄请使用 /qs create。");
            return 0;
        }
        ChestShop shop = ChestShopManager.get().create(player, player.serverLevel().dimension().location().toString(),
                pos, player.serverLevel().getBlockEntity(pos), player.getMainHandItem(), buyPrice, sellPrice);
        if (shop == null) return 0;
        ChestShopGui.openOwner(player, shop);
        return 1;
    }

    private static int removeShop(ServerPlayer player) {
        ChestShop shop = resolveTargetOrSelectedShop(player);
        if (shop == null) {
            MessageUtil.sendError(player, MODULE, "请将准星对准要移除的箱子商店。");
            return 0;
        }
        if (!shop.ownerUuid.equals(player.getUUID()) && !player.hasPermissions(2)) {
            MessageUtil.sendError(player, MODULE, "只有店主或管理员可以移除该箱子商店！");
            return 0;
        }
        ChestShopGui.confirmDelete(player, shop);
        return 1;
    }

    private static int updatePrice(ServerPlayer player, double buyPrice, double sellPrice) {
        ChestShop shop = resolveTargetOrSelectedShop(player);
        if (shop == null || (!shop.canManage(player.getUUID()) && !player.hasPermissions(2))) {
            MessageUtil.sendError(player, MODULE, "请对准您有管理权限的箱子商店。");
            return 0;
        }
        if (!ChestShopManager.get().setPrices(player, shop, buyPrice, sellPrice)) {
            MessageUtil.sendError(player, MODULE, "请输入有效非负价格，且不能同时为零。");
            return 0;
        }
        MessageUtil.sendSuccess(player, MODULE, "商店价格已保存。");
        return 1;
    }
}
