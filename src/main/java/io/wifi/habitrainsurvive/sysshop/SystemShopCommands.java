package io.wifi.habitrainsurvive.sysshop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public class SystemShopCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"shop", "mall", "sysshop"}) {
            dispatcher.register(Commands.literal(root)
                    .executes(ctx -> {
                        SystemShopGui.openCategoryView(ctx.getSource().getPlayerOrException(), "building");
                        return 1;
                    })
                    .then(Commands.literal("admin").requires(SystemShopManager::authorized)
                            .executes(ctx -> {
                                SystemShopAdminGui.open(ctx.getSource().getPlayerOrException());
                                return 1;
                            }))
                    .then(Commands.literal("addcategory").requires(SystemShopManager::authorized)
                            .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                                ServerPlayer admin = ctx.getSource().getPlayerOrException();
                                try {
                                    SystemShopCategory category = SystemShopManager.get().createCategory(admin, StringArgumentType.getString(ctx, "name"));
                                    MessageUtil.sendSuccess(admin, "系统商城", "分类已创建并保存：" + category.displayName + "（ID: " + category.id + "）"); return 1;
                                } catch (IllegalArgumentException error) { MessageUtil.sendError(admin, "系统商城", error.getMessage()); return 0; }
                            })))
                    .then(Commands.literal("open")
                            .then(Commands.argument("category", StringArgumentType.word())
                                    .executes(ctx -> {
                                        SystemShopGui.openCategoryView(ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "category"));
                                        return 1;
                                    })))
                    .then(Commands.literal("add").requires(SystemShopManager::authorized)
                            .executes(ctx -> { SystemShopAdminGui.openAddSelector(ctx.getSource().getPlayerOrException()); return 1; })
                            .then(Commands.argument("category", StringArgumentType.word())
                                    .then(Commands.argument("buyPrice", DoubleArgumentType.doubleArg(0, SystemShopPricing.MAX_PRICE))
                                            .then(Commands.argument("sellPrice", DoubleArgumentType.doubleArg(0, SystemShopPricing.MAX_PRICE))
                                                    .executes(ctx -> {
                                                        ServerPlayer admin = ctx.getSource().getPlayerOrException();
                                                        try {
                                                            SystemShopManager manager = SystemShopManager.get();
                                                            manager.addItem(admin, manager.getCategory(StringArgumentType.getString(ctx, "category")),
                                                                    admin.getMainHandItem(), DoubleArgumentType.getDouble(ctx, "buyPrice"),
                                                                    DoubleArgumentType.getDouble(ctx, "sellPrice"));
                                                            MessageUtil.sendSuccess(admin, "系统商城", "已上架并保存商品，可通过 /shop admin 调整动态价格");
                                                            return 1;
                                                        } catch (IllegalArgumentException error) {
                                                            MessageUtil.sendError(admin, "系统商城", error.getMessage());
                                                            return 0;
                                                        }
                                                    }))))));
        }
    }
}
