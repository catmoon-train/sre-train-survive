package io.wifi.habitrainsurvive.auction;

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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

public class AuctionCommands {
    private static final String MODULE = "拍卖行";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"ah", "auction", "auc"}) {
            dispatcher.register(Commands.literal(root)
                    .executes(ctx -> {
                        AuctionGui.open(ctx.getSource().getPlayerOrException(), 1);
                        return 1;
                    })
                    .then(Commands.literal("gui")
                            .executes(ctx -> {
                                AuctionGui.open(ctx.getSource().getPlayerOrException(), 1);
                                return 1;
                            })
                            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                    .executes(ctx -> {
                                        AuctionGui.open(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "page"));
                                        return 1;
                                    })))
                    .then(Commands.literal("sell")
                            .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                                    .executes(ctx -> createListing(
                                            ctx.getSource().getPlayerOrException(),
                                            false,
                                            DoubleArgumentType.getDouble(ctx, "price"),
                                            DoubleArgumentType.getDouble(ctx, "price"),
                                            1440L
                                    ))
                                    .then(Commands.argument("durationMinutes", LongArgumentType.longArg(1, 10080))
                                            .executes(ctx -> createListing(
                                                    ctx.getSource().getPlayerOrException(),
                                                    false,
                                                    DoubleArgumentType.getDouble(ctx, "price"),
                                                    DoubleArgumentType.getDouble(ctx, "price"),
                                                    LongArgumentType.getLong(ctx, "durationMinutes")
                                            )))))
                    .then(Commands.literal("bidstart")
                            .then(Commands.argument("startPrice", DoubleArgumentType.doubleArg(0.01))
                                    .then(Commands.argument("buyoutPrice", DoubleArgumentType.doubleArg(0.0))
                                            .executes(ctx -> createListing(
                                                    ctx.getSource().getPlayerOrException(),
                                                    true,
                                                    DoubleArgumentType.getDouble(ctx, "startPrice"),
                                                    DoubleArgumentType.getDouble(ctx, "buyoutPrice"),
                                                    60L
                                            ))
                                            .then(Commands.argument("durationMinutes", LongArgumentType.longArg(1, 10080))
                                                    .executes(ctx -> createListing(
                                                            ctx.getSource().getPlayerOrException(),
                                                            true,
                                                            DoubleArgumentType.getDouble(ctx, "startPrice"),
                                                            DoubleArgumentType.getDouble(ctx, "buyoutPrice"),
                                                            LongArgumentType.getLong(ctx, "durationMinutes")
                                                    ))))))
                    .then(Commands.literal("buy")
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .executes(ctx -> {
                                        AuctionManager.get().buyNow(
                                                ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "id")
                                        );
                                        return 1;
                                    })))
                    .then(Commands.literal("bid")
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                            .executes(ctx -> {
                                                AuctionManager.get().placeBid(
                                                        ctx.getSource().getPlayerOrException(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        DoubleArgumentType.getDouble(ctx, "amount")
                                                );
                                                return 1;
                                            }))))
                    .then(Commands.literal("cancel")
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .executes(ctx -> {
                                        AuctionManager.get().cancelListing(
                                                ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "id")
                                        );
                                        return 1;
                                    })))
                    .then(Commands.literal("claim")
                            .executes(ctx -> {
                                AuctionManager.get().claimAllMailbox(ctx.getSource().getPlayerOrException());
                                return 1;
                            }))
                    .then(Commands.literal("my")
                            .executes(ctx -> showMyListings(ctx.getSource().getPlayerOrException())))
                    .then(Commands.literal("search")
                            .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                    .executes(ctx -> searchListings(
                                            ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "keyword")
                                    )))));
        }
    }

    private static int createListing(ServerPlayer player, boolean biddingMode, double startOrBuyPrice, double buyoutPrice, long durationMinutes) {
        List<AuctionListing> mine = AuctionManager.get().getPlayerListings(player.getUUID());
        if (mine.size() >= AuctionManager.get().getMaxListingsPerPlayer() && !player.hasPermissions(2)) {
            MessageUtil.sendError(player, MODULE, "您同时在售的拍品数量已达上限 (" + AuctionManager.get().getMaxListingsPerPlayer() + ")！");
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            MessageUtil.sendError(player, MODULE, "请主手持有要上架拍卖的物品！");
            return 0;
        }
        if (biddingMode && buyoutPrice > 0 && buyoutPrice < startOrBuyPrice) {
            MessageUtil.sendError(player, MODULE, "一口价不能低于起拍价！");
            return 0;
        }

        ItemStack toSell = held.copy();
        String json = ItemSerializer.serialize(toSell, player.server.registryAccess());
        String displayName = toSell.getHoverName().getString();
        int count = toSell.getCount();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        String id = AuctionManager.get().nextAuctionId();
        AuctionListing listing = new AuctionListing(
                id,
                player.getUUID(),
                player.getGameProfile().getName(),
                json,
                displayName,
                count,
                biddingMode,
                startOrBuyPrice,
                buyoutPrice,
                durationMinutes
        );
        AuctionManager.get().addListing(listing);

        MessageUtil.sendSuccess(player, MODULE, String.format("成功上架拍品 #%s [%s x%d] (%s: %s)！",
                id, displayName, count,
                biddingMode ? "起拍价" : "一口价",
                EconomyManager.get().format(startOrBuyPrice)));
        return 1;
    }

    private static int showMyListings(ServerPlayer player) {
        List<AuctionListing> mine = AuctionManager.get().getPlayerListings(player.getUUID());
        if (mine.isEmpty()) {
            MessageUtil.sendInfo(player, MODULE, "您当前没有正在上架的拍品。");
            return 1;
        }
        player.sendSystemMessage(Component.literal("=== 您正在上架的拍品 (" + mine.size() + ") ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (AuctionListing a : mine) {
            player.sendSystemMessage(Component.literal("#" + a.id + " [" + a.itemDisplayName + " x" + a.count + "] ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("价格: " + EconomyManager.get().format(a.startOrBuyPrice) + " 剩余: " + (a.getRemainingSeconds() / 60) + "分钟 ").withStyle(ChatFormatting.WHITE))
                    .append(MessageUtil.commandButton("下架取回", "/ah cancel " + a.id, "点击下架此拍品", ChatFormatting.RED)));
        }
        return 1;
    }

    private static int searchListings(ServerPlayer player, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        List<AuctionListing> matched = AuctionManager.get().getActiveListingsSorted().stream()
                .filter(a -> a.itemDisplayName != null && a.itemDisplayName.toLowerCase(Locale.ROOT).contains(lower))
                .toList();
        if (matched.isEmpty()) {
            MessageUtil.sendInfo(player, MODULE, "未找到包含 [" + keyword + "] 的在售拍品。");
            return 1;
        }
        player.sendSystemMessage(Component.literal("=== 拍卖行搜索结果 (" + matched.size() + ") ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (AuctionListing a : matched) {
            player.sendSystemMessage(Component.literal("#" + a.id + " [" + a.itemDisplayName + " x" + a.count + "] ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("卖家: " + a.sellerName + " | " + (a.biddingMode ? "当前竞价: " + EconomyManager.get().format(Math.max(a.startOrBuyPrice, a.currentBid)) : "一口价: " + EconomyManager.get().format(a.startOrBuyPrice)) + " ").withStyle(ChatFormatting.WHITE))
                    .append(a.biddingMode
                            ? MessageUtil.commandButton("加价5%", "/ah bid " + a.id + " " + Math.round(Math.max(a.startOrBuyPrice, a.currentBid * 1.05) * 100.0) / 100.0, "点击参与竞拍", ChatFormatting.YELLOW)
                            : MessageUtil.commandButton("立即购买", "/ah buy " + a.id, "点击一口价购买", ChatFormatting.GREEN)));
        }
        return 1;
    }
}
