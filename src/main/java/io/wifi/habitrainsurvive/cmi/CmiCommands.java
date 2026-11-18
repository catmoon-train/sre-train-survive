package io.wifi.habitrainsurvive.cmi;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.LocalDate;
import java.util.*;

public class CmiCommands {
    private static final String MODULE = "CMI";
    private static final Random RANDOM = new Random();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 1. 家系统: /sethome, /delhome, /home, /homes
        dispatcher.register(Commands.literal("sethome")
                .executes(ctx -> setHome(ctx.getSource().getPlayerOrException(), "home"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setHome(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")))));

        dispatcher.register(Commands.literal("delhome")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                            if (prof.homes.remove(name) == null) {
                                MessageUtil.sendError(p, MODULE, "未找到名为 [" + name + "] 的家。");
                                return 0;
                            }
                            MessageUtil.sendSuccess(p, MODULE, "已删除家 [" + name + "]。");
                            return 1;
                        })));

        dispatcher.register(Commands.literal("home")
                .executes(ctx -> tpHome(ctx.getSource().getPlayerOrException(), "home"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> tpHome(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")))));

        dispatcher.register(Commands.literal("homes")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                    int currentLimit = CmiManager.get().getMaxHomesForPlayer(p.getUUID());
                    if (currentLimit < Integer.MAX_VALUE) p.sendSystemMessage(Component.literal("家额度: " + prof.homes.size() + "/" + currentLimit + " ")
                            .withStyle(ChatFormatting.GOLD).append(MessageUtil.commandButton("购买额外家上限 +1", "/homes buy",
                                    "本次需要 " + EconomyManager.get().format(CmiManager.get().nextHomeSlotPrice(p.getUUID())), ChatFormatting.GREEN)));
                    if (prof.homes.isEmpty()) {
                        MessageUtil.sendInfo(p, MODULE, "您还未设置任何家，使用 /sethome [名称] 设置。");
                        return 1;
                    }
                    int maxHomes = CmiManager.get().getMaxHomesForPlayer(p.getUUID());
                    p.sendSystemMessage(Component.literal("=== 您的家列表 (" + prof.homes.size() + "/" + maxHomes + ") ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                    prof.homes.forEach((name, pos) -> p.sendSystemMessage(
                            Component.literal("- [" + name + "] ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(pos.toShortString() + " ").withStyle(ChatFormatting.GRAY))
                                    .append(MessageUtil.commandButton("传送", "/home " + name, "点击传送回家", ChatFormatting.GREEN))
                                    .append(Component.literal(" "))
                                    .append(MessageUtil.commandButton("删除", "/delhome " + name, "点击删除该家", ChatFormatting.RED))
                    ));
                    return 1;
                })
                .then(Commands.literal("buy").executes(ctx -> { HomeUpgradeGui.open(ctx.getSource().getPlayerOrException()); return 1; })));
        dispatcher.register(Commands.literal("buyhome").executes(ctx -> { HomeUpgradeGui.open(ctx.getSource().getPlayerOrException()); return 1; }));

        // 2. 地标系统: /setwarp, /delwarp, /warp, /warps
        dispatcher.register(Commands.literal("setwarp")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setWarp(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name"), 0.0))
                        .then(Commands.argument("cost", DoubleArgumentType.doubleArg(0.0))
                                .executes(ctx -> setWarp(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name"), DoubleArgumentType.getDouble(ctx, "cost"))))));

        dispatcher.register(Commands.literal("delwarp")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            if (CmiManager.get().getWarps().remove(name) == null) {
                                MessageUtil.sendError(p, MODULE, "地标 [" + name + "] 不存在。");
                                return 0;
                            }
                            MessageUtil.sendSuccess(p, MODULE, "已删除服务器地标 [" + name + "]。");
                            io.wifi.habitrainsurvive.data.ServerDataManager.saveAll();
                            return 1;
                        })));

        dispatcher.register(Commands.literal("warp")
                .executes(ctx -> { io.wifi.habitrainsurvive.landmark.LandmarkGui.open(ctx.getSource().getPlayerOrException()); return 1; })
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            return io.wifi.habitrainsurvive.landmark.LandmarkManager.teleport(p, name) ? 1 : 0;
                        })));

        dispatcher.register(Commands.literal("warps")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    io.wifi.habitrainsurvive.landmark.LandmarkGui.open(p);
                    return 1;
                }));

        // 3. 主城与返回: /setspawn, /spawn, /back
        dispatcher.register(Commands.literal("setspawn")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    CmiManager.get().setServerSpawn(GlobalPosInfo.fromPlayer(p));
                    io.wifi.habitrainsurvive.data.ServerDataManager.saveAll();
                    MessageUtil.sendSuccess(p, MODULE, "已将当前位置设置为服务器主城出生点 (/spawn)。");
                    return 1;
                }));

        dispatcher.register(Commands.literal("spawn")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    io.wifi.habitrainsurvive.hub.HubManager.get().teleport(p);
                    return 1;
                }));

        dispatcher.register(Commands.literal("back")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                    if (prof.lastLocation == null) {
                        MessageUtil.sendError(p, MODULE, "没有可返回的上一个位置记录。");
                        return 0;
                    }
                    GlobalPosInfo target = prof.lastLocation;
                    TeleportWarmupManager.get().scheduleTeleportToPos(p, "上一位置", target);
                    return 1;
                }));

        // 4. 全套 TPA 互传系统: /tpa, /tpahere, /tpaall, /tpaccept (/tpyes), /tpdeny (/tpno), /tpcancel, /tpatoggle
        dispatcher.register(Commands.literal("tpa")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> sendTpa(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "target"), false))));

        dispatcher.register(Commands.literal("tpahere")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> sendTpa(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "target"), true))));

        dispatcher.register(Commands.literal("tpaall")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer sender = ctx.getSource().getPlayerOrException();
                    if (!checkTpaCooldown(sender)) return 0;
                    int count = 0;
                    for (ServerPlayer online : sender.server.getPlayerList().getPlayers()) {
                        if (!online.getUUID().equals(sender.getUUID())) {
                            count += sendTpa(sender, online, true, true);
                        }
                    }
                    if (count > 0) CmiManager.get().markTpaSent(sender.getUUID());
                    MessageUtil.sendSuccess(sender, MODULE, "已向全服 " + count + " 名在线玩家发送集合传送请求 (/tpahere)！");
                    return 1;
                }));

        for (String acceptAlias : new String[]{"tpaccept", "tpyes"}) {
            dispatcher.register(Commands.literal(acceptAlias)
                    .executes(ctx -> {
                        ServerPlayer target = ctx.getSource().getPlayerOrException();
                        TpaRequest req = CmiManager.get().removeTpaRequest(target.getUUID());
                        if (req == null) {
                            MessageUtil.sendError(target, MODULE, "当前没有待处理的传送请求或请求已过期。");
                            return 0;
                        }
                        ServerPlayer sender = target.server.getPlayerList().getPlayer(req.sender);
                        if (sender == null) {
                            MessageUtil.sendError(target, MODULE, "请求发起者已离线。");
                            return 0;
                        }
                        MessageUtil.sendSuccess(target, MODULE, "已接受来自 " + sender.getName().getString() + " 的传送请求！");
                        MessageUtil.sendSuccess(sender, MODULE, target.getName().getString() + " 接受了您的传送请求！");
                        if (req.tpaHere) {
                            TeleportWarmupManager.get().scheduleTeleport(target, sender.getName().getString() + " 身边", () -> {
                                if (!sender.hasDisconnected()) GlobalPosInfo.fromPlayer(sender).teleportAsync(target,
                                        () -> !sender.hasDisconnected(),
                                        moved -> MessageUtil.sendSuccess(moved, MODULE, "已传送至 " + sender.getName().getString() + " 身边！"));
                            });
                        } else {
                            TeleportWarmupManager.get().scheduleTeleport(sender, target.getName().getString() + " 身边", () -> {
                                if (!target.hasDisconnected()) GlobalPosInfo.fromPlayer(target).teleportAsync(sender,
                                        () -> !target.hasDisconnected(),
                                        moved -> MessageUtil.sendSuccess(moved, MODULE, "已传送至 " + target.getName().getString() + " 身边！"));
                            });
                        }
                        return 1;
                    }));
        }

        for (String denyAlias : new String[]{"tpdeny", "tpno"}) {
            dispatcher.register(Commands.literal(denyAlias)
                    .executes(ctx -> {
                        ServerPlayer target = ctx.getSource().getPlayerOrException();
                        TpaRequest req = CmiManager.get().removeTpaRequest(target.getUUID());
                        if (req == null) {
                            MessageUtil.sendError(target, MODULE, "当前没有待处理的传送请求。");
                            return 0;
                        }
                        ServerPlayer sender = target.server.getPlayerList().getPlayer(req.sender);
                        if (sender != null) {
                            MessageUtil.sendError(sender, MODULE, target.getName().getString() + " 拒绝了您的传送请求。");
                        }
                        MessageUtil.sendInfo(target, MODULE, "已拒绝该传送请求。");
                        return 1;
                    }));
        }

        dispatcher.register(Commands.literal("tpcancel")
                .executes(ctx -> {
                    ServerPlayer sender = ctx.getSource().getPlayerOrException();
                    TeleportWarmupManager.get().cancel(sender.getUUID());
                    if (CmiManager.get().cancelOutgoingTpa(sender.getUUID())) {
                        MessageUtil.sendSuccess(sender, MODULE, "已取消您发出的待处理传送请求。");
                    } else {
                        MessageUtil.sendInfo(sender, MODULE, "您当前没有正在等待对方处理的传送请求。");
                    }
                    return 1;
                }));

        dispatcher.register(Commands.literal("tpatoggle")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                    prof.tpaToggle = !prof.tpaToggle;
                    MessageUtil.sendSuccess(p, MODULE, "接收他人 TPA 传送请求已切换为: " + (prof.tpaToggle ? "允许" : "免打扰拒绝"));
                    return 1;
                }));

        // 5. 随机传送 /rtp 与视线闪现 /jump (/thru)
        dispatcher.register(Commands.literal("rtp")
                .executes(ctx -> executeRtp(ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("world", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                        .executes(ctx -> io.wifi.habitrainsurvive.world.WorldManager.get().rtp(ctx.getSource().getPlayerOrException(),
                                net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "world").toString()) ? 1 : 0)));

        for (String jumpAlias : new String[]{"jump", "thru"}) {
            dispatcher.register(Commands.literal(jumpAlias)
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        HitResult hit = p.pick(120.0, 0.0f, false);
                        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) {
                            MessageUtil.sendError(p, MODULE, "视线 120 格内未找到目标方块！");
                            return 0;
                        }
                        BlockPos pos = bhr.getBlockPos().above();
                        CmiManager.get().recordLastLocation(p);
                        p.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                        MessageUtil.sendSuccess(p, MODULE, "已闪现至视线目标位置！");
                        return 1;
                    }));
        }

        // 6. 全套随身工作站: /ec, /wb, /anvil, /grindstone, /stonecutter, /loom, /cartography, /smithing, /trash
        for (String ecAlias : new String[]{"enderchest", "ec"}) {
            dispatcher.register(Commands.literal(ecAlias)
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        p.openMenu(new SimpleMenuProvider(
                                (id, inv, player) -> ChestMenu.threeRows(id, inv, p.getEnderChestInventory()),
                                Component.literal("随身末影箱")
                        ));
                        return 1;
                    }));
        }

        for (String wbAlias : new String[]{"workbench", "wb", "craft"}) {
            dispatcher.register(Commands.literal(wbAlias)
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        p.openMenu(new SimpleMenuProvider(
                                (id, inv, player) -> new CraftingMenu(id, inv, ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())) {
                                    @Override
                                    public boolean stillValid(Player playerIn) {
                                        return true;
                                    }
                                },
                                Component.literal("随身工作台")
                        ));
                        return 1;
                    }));
        }

        dispatcher.register(Commands.literal("anvil")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.openMenu(new SimpleMenuProvider(
                            (id, inv, player) -> new AnvilMenu(id, inv, ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())) {
                                @Override
                                public boolean stillValid(Player playerIn) {
                                    return true;
                                }
                            },
                            Component.literal("随身铁砧")
                    ));
                    return 1;
                }));

        dispatcher.register(Commands.literal("grindstone")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.openMenu(new SimpleMenuProvider(
                            (id, inv, player) -> new GrindstoneMenu(id, inv, ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())) {
                                @Override
                                public boolean stillValid(Player playerIn) {
                                    return true;
                                }
                            },
                            Component.literal("随身砂轮")
                    ));
                    return 1;
                }));

        dispatcher.register(Commands.literal("stonecutter")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.openMenu(new SimpleMenuProvider(
                            (id, inv, player) -> new StonecutterMenu(id, inv, ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())) {
                                @Override
                                public boolean stillValid(Player playerIn) {
                                    return true;
                                }
                            },
                            Component.literal("随身切石机")
                    ));
                    return 1;
                }));

        dispatcher.register(Commands.literal("loom")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.openMenu(new SimpleMenuProvider(
                            (id, inv, player) -> new LoomMenu(id, inv, ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())) {
                                @Override
                                public boolean stillValid(Player playerIn) {
                                    return true;
                                }
                            },
                            Component.literal("随身织布机")
                    ));
                    return 1;
                }));

        dispatcher.register(Commands.literal("cartography")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.openMenu(new SimpleMenuProvider(
                            (id, inv, player) -> new CartographyTableMenu(id, inv, ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())) {
                                @Override
                                public boolean stillValid(Player playerIn) {
                                    return true;
                                }
                            },
                            Component.literal("随身制图台")
                    ));
                    return 1;
                }));

        dispatcher.register(Commands.literal("smithing")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.openMenu(new SimpleMenuProvider(
                            (id, inv, player) -> new SmithingMenu(id, inv, ContainerLevelAccess.create(p.serverLevel(), p.blockPosition())) {
                                @Override
                                public boolean stillValid(Player playerIn) {
                                    return true;
                                }
                            },
                            Component.literal("随身锻造台")
                    ));
                    return 1;
                }));

        for (String trashAlias : new String[]{"trash", "dispose"}) {
            dispatcher.register(Commands.literal(trashAlias)
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        p.openMenu(new SimpleMenuProvider(
                                (id, inv, player) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, new SimpleContainer(36), 4),
                                Component.literal("垃圾桶 (关闭后自动销毁)")
                        ));
                        return 1;
                    }));
        }

        // 7. 管理员在线查包与末影箱审查: /invsee, /endersee
        dispatcher.register(Commands.literal("invsee")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> openInvSee(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "target")))));

        for (String ecSeeAlias : new String[]{"endersee", "ecsee"}) {
            dispatcher.register(Commands.literal(ecSeeAlias)
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("target", EntityArgument.player())
                            .executes(ctx -> {
                                ServerPlayer admin = ctx.getSource().getPlayerOrException();
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                admin.openMenu(new SimpleMenuProvider(
                                        (id, inv, p) -> ChestMenu.threeRows(id, inv, target.getEnderChestInventory()),
                                        Component.literal("末影箱审查: " + target.getName().getString())
                                ));
                                return 1;
                            })));
        }

        // 8. 生存实用功能: /daily, /playtime, /worth, /setworth, /sellhand, /sellall, /bounty, /mail, /helpop, /near, /ping, /suicide, /ext, /glow, /nick, /ignore
        for (String dailyAlias : new String[]{"daily", "signin"}) {
            dispatcher.register(Commands.literal(dailyAlias)
                    .executes(ctx -> claimDailyReward(ctx.getSource().getPlayerOrException())));
        }

        dispatcher.register(Commands.literal("playtime")
                .executes(ctx -> showPlaytime(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> showPlaytime(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));

        dispatcher.register(Commands.literal("worth")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ItemStack held = p.getMainHandItem();
                    if (held.isEmpty()) {
                        MessageUtil.sendInfo(p, MODULE, "请手持物品查看官方回收估价，或使用 /sellhand 直接出售主手物品。");
                        return 0;
                    }
                    double unit = CmiManager.get().getItemWorth(held.getItem());
                    if (unit <= 0) {
                        MessageUtil.sendError(p, MODULE, "主手物品 [" + held.getHoverName().getString() + "] 暂不支持官方系统回收。");
                        return 0;
                    }
                    MessageUtil.sendInfo(p, MODULE, String.format("物品 [%s] 官方回收单价: %s | 当前手中 %d 个总价: %s",
                            held.getHoverName().getString(),
                            EconomyManager.get().format(unit),
                            held.getCount(),
                            EconomyManager.get().format(unit * held.getCount())));
                    return 1;
                }));

        dispatcher.register(Commands.literal("setworth")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.0))
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            ItemStack held = p.getMainHandItem();
                            if (held.isEmpty()) {
                                MessageUtil.sendError(p, MODULE, "请主手拿着要设定系统回收价的物品！");
                                return 0;
                            }
                            double price = DoubleArgumentType.getDouble(ctx, "price");
                            CmiManager.get().setItemWorth(held.getItem(), price);
                            MessageUtil.sendSuccess(p, MODULE, "已将 [" + held.getHoverName().getString() + "] 的系统官方回收单价设为: " + EconomyManager.get().format(price));
                            return 1;
                        })));

        dispatcher.register(Commands.literal("sellhand")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ItemStack held = p.getMainHandItem();
                    if (held.isEmpty()) {
                        MessageUtil.sendError(p, MODULE, "请主手拿着要出售给系统的物品！");
                        return 0;
                    }
                    double unit = CmiManager.get().getItemWorth(held.getItem());
                    if (unit <= 0) {
                        MessageUtil.sendError(p, MODULE, "该物品不在系统官方回收名录中！");
                        return 0;
                    }
                    int count = held.getCount();
                    double total = Math.round(unit * count * 100.0) / 100.0;
                    String name = held.getHoverName().getString();
                    p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    EconomyManager.get().deposit(p.getUUID(), total, "SellHand " + name);
                    MessageUtil.sendSuccess(p, MODULE, "已向系统出售 " + count + "x [" + name + "]，获得 " + EconomyManager.get().format(total));
                    MessageUtil.playDing(p);
                    return 1;
                }));

        dispatcher.register(Commands.literal("sellall")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    int totalItems = 0;
                    double totalEarned = 0.0;
                    for (int i = 0; i < p.getInventory().items.size(); i++) {
                        ItemStack st = p.getInventory().items.get(i);
                        if (!st.isEmpty()) {
                            double unit = CmiManager.get().getItemWorth(st.getItem());
                            if (unit > 0) {
                                totalItems += st.getCount();
                                totalEarned += unit * st.getCount();
                                p.getInventory().items.set(i, ItemStack.EMPTY);
                            }
                        }
                    }
                    if (totalItems == 0) {
                        MessageUtil.sendError(p, MODULE, "您背包中没有可被系统回收的物品。");
                        return 0;
                    }
                    totalEarned = Math.round(totalEarned * 100.0) / 100.0;
                    EconomyManager.get().deposit(p.getUUID(), totalEarned, "SellAll Inventory");
                    MessageUtil.sendSuccess(p, MODULE, "成功向系统一键出售背包内共计 " + totalItems + " 件可回收物品，获得总额: " + EconomyManager.get().format(totalEarned));
                    MessageUtil.playDing(p);
                    return 1;
                }));

        dispatcher.register(Commands.literal("bounty")
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            var map = CmiManager.get().getBounties();
                            if (map.isEmpty()) {
                                MessageUtil.sendInfo(p, "悬赏榜", "当前没有玩家被悬赏通缉。");
                                return 1;
                            }
                            p.sendSystemMessage(Component.literal("=== 全服悬赏通缉榜 ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                            map.forEach((uuid, amount) -> {
                                PlayerProfileData prof = CmiManager.get().getOrCreateProfile(uuid, uuid.toString().substring(0, 8));
                                p.sendSystemMessage(Component.literal("🎯 目标: " + prof.lastKnownName + " - 赏金池: " + EconomyManager.get().format(amount)).withStyle(ChatFormatting.RED));
                            });
                            return 1;
                        }))
                .then(Commands.literal("add")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(10.0))
                                        .executes(ctx -> {
                                            ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            double amount = DoubleArgumentType.getDouble(ctx, "amount");
                                            if (sender.getUUID().equals(target.getUUID())) {
                                                MessageUtil.sendError(sender, "悬赏榜", "不能悬赏自己！");
                                                return 0;
                                            }
                                            if (!EconomyManager.get().withdraw(sender.getUUID(), amount, "Bounty on " + target.getName().getString())) {
                                                MessageUtil.sendError(sender, "悬赏榜", "您的金币不足以发布该悬赏！");
                                                return 0;
                                            }
                                            double newTotal = CmiManager.get().getBounties().merge(target.getUUID(), amount, Double::sum);
                                            sender.server.getPlayerList().broadcastSystemMessage(
                                                    MessageUtil.prefix("悬赏通缉", ChatFormatting.RED)
                                                            .append(Component.literal(sender.getName().getString() + " 为 " + target.getName().getString()
                                                                    + " 追加了 " + EconomyManager.get().format(amount) + " 悬赏！当前总赏金: " + EconomyManager.get().format(newTotal)).withStyle(ChatFormatting.YELLOW)),
                                                    false
                                            );
                                            return 1;
                                        })))));

        dispatcher.register(Commands.literal("mail")
                .then(Commands.literal("read")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                            if (prof.mailbox.isEmpty()) {
                                MessageUtil.sendInfo(p, "信箱", "您的信箱目前没有任何邮件。");
                                return 1;
                            }
                            p.sendSystemMessage(Component.literal("=== 您的信箱邮件 (" + prof.mailbox.size() + ") ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                            for (int i = 0; i < prof.mailbox.size(); i++) {
                                p.sendSystemMessage(Component.literal((i + 1) + ". " + prof.mailbox.get(i)).withStyle(ChatFormatting.WHITE));
                            }
                            return 1;
                        }))
                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            CmiManager.get().getOrCreateProfile(p).mailbox.clear();
                            MessageUtil.sendSuccess(p, "信箱", "已清空您的全部信箱邮件。");
                            return 1;
                        }))
                .then(Commands.literal("send")
                        .then(Commands.argument("targetName", StringArgumentType.word())
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                            String targetName = StringArgumentType.getString(ctx, "targetName");
                                            String msg = StringArgumentType.getString(ctx, "message");
                                            Optional<PlayerProfileData> opt = CmiManager.get().findProfileByName(targetName);
                                            if (opt.isEmpty()) {
                                                MessageUtil.sendError(sender, "信箱", "未找到玩家 [" + targetName + "] 的档案记录。");
                                                return 0;
                                            }
                                            opt.get().mailbox.add("[" + sender.getName().getString() + "]: " + msg);
                                            MessageUtil.sendSuccess(sender, "信箱", "已成功向 " + opt.get().lastKnownName + " 投递离线邮件！");
                                            ServerPlayer online = sender.server.getPlayerList().getPlayer(opt.get().uuid);
                                            if (online != null) {
                                                MessageUtil.sendInfo(online, "信箱", "收到来自 " + sender.getName().getString() + " 的新邮件，输入 /mail read 查看！");
                                            }
                                            return 1;
                                        })))));

        for (String helpAlias : new String[]{"helpop", "staffhelp"}) {
            dispatcher.register(Commands.literal(helpAlias)
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                String msg = StringArgumentType.getString(ctx, "message");
                                Component ticket = MessageUtil.prefix("工单求助", ChatFormatting.RED)
                                        .append(Component.literal("[" + sender.getName().getString() + " @ " + sender.blockPosition().toShortString() + "]: ").withStyle(ChatFormatting.YELLOW))
                                        .append(Component.literal(msg).withStyle(ChatFormatting.WHITE));
                                for (ServerPlayer online : sender.server.getPlayerList().getPlayers()) {
                                    if (online.hasPermissions(2)) {
                                        online.sendSystemMessage(ticket);
                                        MessageUtil.playDing(online);
                                    }
                                }
                                MessageUtil.sendSuccess(sender, MODULE, "您的求助工单已发送给所有在线管理员！");
                                return 1;
                            })));
        }

        dispatcher.register(Commands.literal("near")
                .executes(ctx -> showNearPlayers(ctx.getSource().getPlayerOrException(), 100))
                .then(Commands.argument("radius", IntegerArgumentType.integer(10, 1000))
                        .executes(ctx -> showNearPlayers(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "radius")))));

        dispatcher.register(Commands.literal("ping")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    MessageUtil.sendInfo(p, MODULE, "您的当前网络延迟 (Ping): " + p.connection.latency() + " ms");
                    return 1;
                })
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                            ctx.getSource().sendSuccess(() -> Component.literal(target.getName().getString() + " 的网络延迟: " + target.connection.latency() + " ms").withStyle(ChatFormatting.AQUA), false);
                            return 1;
                        })));

        dispatcher.register(Commands.literal("suicide")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.kill();
                    MessageUtil.sendInfo(p, MODULE, "您已选择自我了断脱困。");
                    return 1;
                }));

        for (String extAlias : new String[]{"ext", "extinguish"}) {
            dispatcher.register(Commands.literal(extAlias)
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        p.clearFire();
                        MessageUtil.sendSuccess(p, MODULE, "已熄灭身上的火焰！");
                        return 1;
                    }));
        }

        dispatcher.register(Commands.literal("glow")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.setGlowingTag(!p.isCurrentlyGlowing());
                    MessageUtil.sendSuccess(p, MODULE, "全身高亮发光效果已" + (p.isCurrentlyGlowing() ? "开启" : "关闭"));
                    return 1;
                }));

        dispatcher.register(Commands.literal("nick")
                .then(Commands.argument("nickname", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String raw = StringArgumentType.getString(ctx, "nickname");
                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                            if (raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("clear")) {
                                prof.nickname = null;
                                p.setCustomName(null);
                                MessageUtil.sendSuccess(p, MODULE, "已恢复您的默认玩家名称。");
                            } else {
                                String colored = raw.replace('&', '§');
                                prof.nickname = colored;
                                p.setCustomName(Component.literal(colored));
                                p.setCustomNameVisible(true);
                                MessageUtil.sendSuccess(p, MODULE, "您的显示昵称已更新为: " + colored);
                            }
                            return 1;
                        })));

        dispatcher.register(Commands.literal("ignore")
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                            if (prof.ignoredPlayers.contains(target.getUUID())) {
                                prof.ignoredPlayers.remove(target.getUUID());
                                MessageUtil.sendSuccess(p, MODULE, "已取消屏蔽玩家 " + target.getName().getString());
                            } else {
                                prof.ignoredPlayers.add(target.getUUID());
                                MessageUtil.sendSuccess(p, MODULE, "已屏蔽来自 " + target.getName().getString() + " 的私聊消息。");
                            }
                            return 1;
                        })));

        // 9. 服管惩戒与监狱系统: /ban, /tempban, /unban, /warn, /warnings, /setjail, /jail, /unjail, /freeze, /vanish, /sudo, /clearchat
        dispatcher.register(Commands.literal("tempban")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 5256000))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                            String reason = StringArgumentType.getString(ctx, "reason");
                                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(target);
                                            prof.bannedUntil = System.currentTimeMillis() + minutes * 60_000L;
                                            prof.banReason = reason;
                                            target.connection.disconnect(Component.literal("§c您已被管理员封禁 " + minutes + " 分钟！\n§e原因: §f" + reason));
                                            ctx.getSource().sendSuccess(() -> Component.literal("已临时封禁玩家 " + target.getName().getString() + " (" + minutes + "分钟)"), true);
                                            return 1;
                                        })))));

        dispatcher.register(Commands.literal("unban")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("playerName", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "playerName");
                            Optional<PlayerProfileData> opt = CmiManager.get().findProfileByName(name);
                            if (opt.isEmpty()) {
                                ctx.getSource().sendFailure(Component.literal("未找到该玩家的档案。"));
                                return 0;
                            }
                            opt.get().bannedUntil = 0L;
                            opt.get().banReason = "";
                            ctx.getSource().sendSuccess(() -> Component.literal("已解除玩家 " + name + " 的封禁。"), true);
                            return 1;
                        })));

        dispatcher.register(Commands.literal("warn")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                    String reason = StringArgumentType.getString(ctx, "reason");
                                    PlayerProfileData prof = CmiManager.get().getOrCreateProfile(target);
                                    prof.warnings.add("[" + LocalDate.now() + "] " + reason);
                                    MessageUtil.sendError(target, "警告", "您收到了一次管理员警告 (累计 " + prof.warnings.size() + " 次): " + reason);
                                    if (prof.warnings.size() >= 3) {
                                        prof.mutedUntil = System.currentTimeMillis() + 60 * 60_000L;
                                        prof.muteReason = "累计满3次警告自动禁言";
                                        MessageUtil.sendError(target, "警告处罚", "因累计达到 3 次警告，系统已自动禁言您 60 分钟！");
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("已警告玩家 " + target.getName().getString()), true);
                                    return 1;
                                }))));

        dispatcher.register(Commands.literal("setjail")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            CmiManager.get().getJails().put(name, GlobalPosInfo.fromPlayer(p));
                            MessageUtil.sendSuccess(p, MODULE, "已将当前坐标设置为服务器监狱 [" + name + "]。");
                            return 1;
                        })));

        dispatcher.register(Commands.literal("jail")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("jailName", StringArgumentType.word())
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 525600))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                    String jailName = StringArgumentType.getString(ctx, "jailName").toLowerCase(Locale.ROOT);
                                                    int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                                    String reason = StringArgumentType.getString(ctx, "reason");
                                                    GlobalPosInfo jailPos = CmiManager.get().getJails().get(jailName);
                                                    if (jailPos == null) {
                                                        ctx.getSource().sendFailure(Component.literal("监狱 [" + jailName + "] 不存在，请先使用 /setjail 创建！"));
                                                        return 0;
                                                    }
                                                    PlayerProfileData prof = CmiManager.get().getOrCreateProfile(target);
                                                    prof.preJailLocation = GlobalPosInfo.fromPlayer(target);
                                                    prof.jailedUntil = System.currentTimeMillis() + minutes * 60_000L;
                                                    prof.jailName = jailName;
                                                    prof.jailReason = reason;
                                                    io.wifi.habitrainsurvive.world.WorldManager.get().recover(() -> jailPos.teleportAsync(target));
                                                    MessageUtil.sendError(target, "监狱系统", "您已被关押至监狱 [" + jailName + "] " + minutes + " 分钟！原因: " + reason);
                                                    ctx.getSource().sendSuccess(() -> Component.literal("已将玩家 " + target.getName().getString() + " 关入监狱 [" + jailName + "]"), true);
                                                    return 1;
                                                }))))));

        dispatcher.register(Commands.literal("unjail")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(target);
                            prof.jailedUntil = 0L;
                            if (prof.preJailLocation != null) {
                                io.wifi.habitrainsurvive.world.WorldManager.get().recover(() -> prof.preJailLocation.teleportAsync(target));
                                prof.preJailLocation = null;
                            }
                            MessageUtil.sendSuccess(target, "监狱系统", "管理员已提前将您从监狱释放！");
                            ctx.getSource().sendSuccess(() -> Component.literal("已释放玩家 " + target.getName().getString()), true);
                            return 1;
                        })));

        dispatcher.register(Commands.literal("freeze")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(target);
                            prof.frozen = !prof.frozen;
                            prof.lastLocation = GlobalPosInfo.fromPlayer(target);
                            MessageUtil.sendInfo(target, "安全管控", prof.frozen ? "您已被管理员冻结，请配合审查不要下线！" : "您的冻结状态已解除。");
                            ctx.getSource().sendSuccess(() -> Component.literal("玩家 " + target.getName().getString() + " 冻结状态: " + prof.frozen), true);
                            return 1;
                        })));

        for (String vAlias : new String[]{"vanish", "v"}) {
            dispatcher.register(Commands.literal(vAlias)
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                        prof.vanished = !prof.vanished;
                        if (!prof.vanished) {
                            p.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
                        }
                        MessageUtil.sendSuccess(p, MODULE, "管理员隐身巡查模式已" + (prof.vanished ? "开启" : "关闭"));
                        return 1;
                    }));
        }

        dispatcher.register(Commands.literal("sudo")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                    String rawCommand = StringArgumentType.getString(ctx, "command");
                                    String cmd = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
                                    target.server.getCommands().performPrefixedCommand(target.createCommandSourceStack(), cmd);
                                    ctx.getSource().sendSuccess(() -> Component.literal("已强制 " + target.getName().getString() + " 执行指令: /" + cmd), true);
                                    return 1;
                                }))));

        for (String ccAlias : new String[]{"clearchat", "cc"}) {
            dispatcher.register(Commands.literal(ccAlias)
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> {
                        for (ServerPlayer online : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                            for (int i = 0; i < 100; i++) {
                                online.sendSystemMessage(Component.literal(" "));
                            }
                            MessageUtil.sendInfo(online, MODULE, "公屏聊天记录已被管理员清理。");
                        }
                        return 1;
                    }));
        }

        // 10. 经典玩家状态与服管指令: /fly, /god, /heal, /feed, /hat, /repair, /top, /gm, /speed, /msg, /r, /broadcast, /clearlag, /mute, /unmute, /whois, /afk, /kit
        dispatcher.register(Commands.literal("fly")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> toggleFly(ctx.getSource().getPlayerOrException(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> toggleFly(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player")))));

        dispatcher.register(Commands.literal("god")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                    prof.godMode = !prof.godMode;
                    MessageUtil.sendSuccess(p, MODULE, "无敌模式 (God Mode) 已" + (prof.godMode ? "开启" : "关闭"));
                    return 1;
                }));

        dispatcher.register(Commands.literal("heal")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> healPlayer(ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> healPlayer(EntityArgument.getPlayer(ctx, "player")))));

        dispatcher.register(Commands.literal("feed")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> feedPlayer(ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> feedPlayer(EntityArgument.getPlayer(ctx, "player")))));

        dispatcher.register(Commands.literal("hat")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ItemStack hand = p.getMainHandItem();
                    if (hand.isEmpty()) {
                        MessageUtil.sendError(p, MODULE, "请手持要戴在头上的物品！");
                        return 0;
                    }
                    ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
                    p.setItemSlot(EquipmentSlot.HEAD, hand.copy());
                    p.setItemInHand(InteractionHand.MAIN_HAND, head);
                    MessageUtil.sendSuccess(p, MODULE, "成功戴上帽子！");
                    return 1;
                }));

        dispatcher.register(Commands.literal("repair")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ItemStack hand = p.getMainHandItem();
                    if (hand.isEmpty() || !hand.isDamageableItem()) {
                        MessageUtil.sendError(p, MODULE, "主手物品无法修复。");
                        return 0;
                    }
                    hand.setDamageValue(0);
                    MessageUtil.sendSuccess(p, MODULE, "已完美修复手中物品！");
                    return 1;
                })
                .then(Commands.literal("all")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                                ItemStack st = p.getInventory().getItem(i);
                                if (!st.isEmpty() && st.isDamageableItem()) {
                                    st.setDamageValue(0);
                                }
                            }
                            MessageUtil.sendSuccess(p, MODULE, "已修复背包内全部可修复装备！");
                            return 1;
                        })));

        dispatcher.register(Commands.literal("top")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    int topY = p.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, p.blockPosition().getX(), p.blockPosition().getZ());
                    CmiManager.get().recordLastLocation(p);
                    p.teleportTo(p.getX(), topY + 1.0, p.getZ());
                    MessageUtil.sendSuccess(p, MODULE, "已传送至顶部最高点。");
                    return 1;
                }));

        dispatcher.register(Commands.literal("gm")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("mode", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String m = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
                            GameType type = switch (m) {
                                case "0", "s", "survival" -> GameType.SURVIVAL;
                                case "1", "c", "creative" -> GameType.CREATIVE;
                                case "2", "a", "adventure" -> GameType.ADVENTURE;
                                case "3", "sp", "spectator" -> GameType.SPECTATOR;
                                default -> null;
                            };
                            if (type == null) {
                                MessageUtil.sendError(p, MODULE, "未知模式！可用: 0/s, 1/c, 2/a, 3/sp");
                                return 0;
                            }
                            p.setGameMode(type);
                            MessageUtil.sendSuccess(p, MODULE, "游戏模式已切换为: " + type.getName());
                            return 1;
                        })));

        dispatcher.register(Commands.literal("speed")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 10.0f))
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            float val = FloatArgumentType.getFloat(ctx, "value");
                            p.getAbilities().setFlyingSpeed(0.05f * val);
                            p.getAbilities().setWalkingSpeed(0.1f * val);
                            p.onUpdateAbilities();
                            MessageUtil.sendSuccess(p, MODULE, "移动与飞行速度倍率已设为: " + val + "x");
                            return 1;
                        })));

        dispatcher.register(Commands.literal("msg")
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> sendPrivateMsg(
                                        ctx.getSource().getPlayerOrException(),
                                        EntityArgument.getPlayer(ctx, "target"),
                                        StringArgumentType.getString(ctx, "message")
                                )))));

        dispatcher.register(Commands.literal("r")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer sender = ctx.getSource().getPlayerOrException();
                            UUID partnerId = CmiManager.get().getLastMsgPartner(sender.getUUID());
                            if (partnerId == null) {
                                MessageUtil.sendError(sender, MODULE, "没有可快速回复的私聊对象。");
                                return 0;
                            }
                            ServerPlayer target = sender.server.getPlayerList().getPlayer(partnerId);
                            if (target == null) {
                                MessageUtil.sendError(sender, MODULE, "对方已离线。");
                                return 0;
                            }
                            return sendPrivateMsg(sender, target, StringArgumentType.getString(ctx, "message"));
                        })));

        dispatcher.register(Commands.literal("broadcast")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String msg = StringArgumentType.getString(ctx, "message");
                            ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                                    MessageUtil.prefix("全服公告", ChatFormatting.RED)
                                            .append(Component.literal(msg).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)),
                                    false
                            );
                            return 1;
                        })));

        dispatcher.register(Commands.literal("clearlag")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    int removed = 0;
                    for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
                        List<Entity> toDiscard = new ArrayList<>();
                        for (Entity entity : level.getAllEntities()) {
                            if (entity instanceof ItemEntity) {
                                toDiscard.add(entity);
                            }
                        }
                        for (Entity e : toDiscard) {
                            e.discard();
                            removed++;
                        }
                    }
                    int total = removed;
                    ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                            MessageUtil.prefix("扫地大妈", ChatFormatting.GOLD)
                                    .append(Component.literal("已清理全服地面掉落物共计 " + total + " 个！").withStyle(ChatFormatting.GREEN)),
                            false
                    );
                    return 1;
                }));

        dispatcher.register(Commands.literal("mute")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 525600))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                            String reason = StringArgumentType.getString(ctx, "reason");
                                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(target);
                                            prof.mutedUntil = System.currentTimeMillis() + minutes * 60_000L;
                                            prof.muteReason = reason;
                                            MessageUtil.sendError(target, MODULE, "您已被管理员禁言 " + minutes + " 分钟，原因: " + reason);
                                            ctx.getSource().sendSuccess(() -> Component.literal("已禁言玩家 " + target.getName().getString()), true);
                                            return 1;
                                        })))));

        dispatcher.register(Commands.literal("unmute")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(target);
                            prof.mutedUntil = 0L;
                            MessageUtil.sendSuccess(target, MODULE, "您的禁言已被解除。");
                            ctx.getSource().sendSuccess(() -> Component.literal("已解除玩家 " + target.getName().getString() + " 的禁言。"), true);
                            return 1;
                        })));

        dispatcher.register(Commands.literal("whois")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            PlayerProfileData prof = CmiManager.get().getOrCreateProfile(target);
                            ctx.getSource().sendSuccess(() -> Component.literal("=== 玩家档案: " + target.getName().getString() + " ===").withStyle(ChatFormatting.GOLD), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("UUID: " + target.getUUID() + " | 延迟: " + target.connection.latency() + "ms").withStyle(ChatFormatting.GRAY), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("坐标: " + GlobalPosInfo.fromPlayer(target).toShortString()).withStyle(ChatFormatting.AQUA), false);
                            ctx.getSource().sendSuccess(() -> Component.literal("生命/饥饿: " + target.getHealth() + " / " + target.getFoodData().getFoodLevel()
                                    + " | 余额: " + EconomyManager.get().format(EconomyManager.get().getBalance(target.getUUID()))
                                    + " | 家数量: " + prof.homes.size() + " | 警告数: " + prof.warnings.size()).withStyle(ChatFormatting.GREEN), false);
                            return 1;
                        })));

        dispatcher.register(Commands.literal("afk")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    PlayerProfileData prof = CmiManager.get().getOrCreateProfile(p);
                    prof.afk = !prof.afk;
                    p.server.getPlayerList().broadcastSystemMessage(
                            Component.literal("* 玩家 " + p.getName().getString() + (prof.afk ? " 暂时离开 (AFK)" : " 回到了游戏")).withStyle(ChatFormatting.GRAY),
                            false
                    );
                    return 1;
                }));

        // 礼包系统: /kit list, /kit claim <name>, /kit create <name> <cooldownSec> <cost>
        dispatcher.register(Commands.literal("kit")
                .executes(ctx -> listKits(ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("list")
                        .executes(ctx -> listKits(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("claim")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> claimKit(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("create")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("cooldownSeconds", LongArgumentType.longArg(0))
                                        .then(Commands.argument("cost", DoubleArgumentType.doubleArg(0.0))
                                                .executes(ctx -> createKitFromInventory(
                                                        ctx.getSource().getPlayerOrException(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        LongArgumentType.getLong(ctx, "cooldownSeconds"),
                                                        DoubleArgumentType.getDouble(ctx, "cost")
                                                ))))))
                .then(Commands.literal("delete")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                                    CmiManager.get().getKits().remove(name);
                                    MessageUtil.sendSuccess(p, MODULE, "已删除礼包 [" + name + "]。");
                                    return 1;
                                }))));
    }

    private static int openInvSee(ServerPlayer admin, ServerPlayer target) {
        SimpleContainer view = new SimpleContainer(45);
        for (int i = 0; i < 36; i++) {
            view.setItem(i, target.getInventory().items.get(i));
        }
        for (int i = 0; i < 4; i++) {
            view.setItem(36 + i, target.getInventory().armor.get(i));
        }
        view.setItem(40, target.getInventory().offhand.get(0));

        view.addListener(container -> {
            for (int i = 0; i < 36; i++) {
                target.getInventory().items.set(i, container.getItem(i));
            }
            for (int i = 0; i < 4; i++) {
                target.getInventory().armor.set(i, container.getItem(36 + i));
            }
            target.getInventory().offhand.set(0, container.getItem(40));
            target.inventoryMenu.broadcastChanges();
        });

        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x5, id, inv, view, 5),
                Component.literal("背包实时管理: " + target.getName().getString())
        ));
        return 1;
    }

    private static int claimDailyReward(ServerPlayer player) {
        PlayerProfileData prof = CmiManager.get().getOrCreateProfile(player);
        long today = LocalDate.now().toEpochDay();
        if (prof.lastDailyClaimDay == today) {
            MessageUtil.sendError(player, "每日签到", "您今天已经领取过签到奖励了，请明天再来！");
            return 0;
        }
        if (prof.lastDailyClaimDay == today - 1) {
            prof.dailyStreak++;
        } else {
            prof.dailyStreak = 1;
        }
        prof.lastDailyClaimDay = today;
        double reward = 100.0 + Math.min(20, prof.dailyStreak) * 25.0;
        EconomyManager.get().deposit(player.getUUID(), reward, "Daily Sign-in Streak " + prof.dailyStreak);
        MessageUtil.sendSuccess(player, "每日签到", String.format("签到成功！您已连续签到 %d 天，获得奖励 %s！",
                prof.dailyStreak, EconomyManager.get().format(reward)));
        MessageUtil.playDing(player);
        return 1;
    }

    private static int showPlaytime(CommandSourceStack source, ServerPlayer target) {
        PlayerProfileData prof = CmiManager.get().getOrCreateProfile(target);
        long sec = prof.totalPlaytimeSeconds;
        long hours = sec / 3600;
        long mins = (sec % 3600) / 60;
        long remSec = sec % 60;
        source.sendSuccess(() -> MessageUtil.prefix(MODULE, ChatFormatting.AQUA)
                .append(Component.literal(String.format("玩家 %s 的累计在线时长: %d小时 %d分 %d秒",
                        target.getName().getString(), hours, mins, remSec)).withStyle(ChatFormatting.GREEN)), false);
        return 1;
    }

    private static int showNearPlayers(ServerPlayer player, int radius) {
        double r2 = (double) radius * radius;
        List<String> found = new ArrayList<>();
        for (ServerPlayer other : player.serverLevel().players()) {
            if (!other.getUUID().equals(player.getUUID())) {
                PlayerProfileData otherProf = CmiManager.get().getOrCreateProfile(other);
                if (otherProf.vanished && !player.hasPermissions(2)) continue;
                double d2 = player.distanceToSqr(other);
                if (d2 <= r2) {
                    found.add(String.format("%s (%.1fm)", other.getName().getString(), Math.sqrt(d2)));
                }
            }
        }
        if (found.isEmpty()) {
            MessageUtil.sendInfo(player, MODULE, "方圆 " + radius + " 格内没有发现其他玩家。");
        } else {
            MessageUtil.sendInfo(player, MODULE, "附近 " + radius + " 格内的玩家: " + String.join(", ", found));
        }
        return 1;
    }

    private static int setHome(ServerPlayer player, String name) {
        PlayerProfileData prof = CmiManager.get().getOrCreateProfile(player);
        String key = name.toLowerCase(Locale.ROOT);
        int maxHomes = CmiManager.get().getMaxHomesForPlayer(player.getUUID());
        if (!prof.homes.containsKey(key) && prof.homes.size() >= maxHomes && !player.hasPermissions(2)) {
            MessageUtil.sendError(player, MODULE, "您已达到最大家数量上限 (" + maxHomes + ")！");
            player.sendSystemMessage(MessageUtil.commandButton("购买额外家上限 +1", "/homes buy", "查看递增价格并确认购买", ChatFormatting.GREEN));
            return 0;
        }
        prof.homes.put(key, GlobalPosInfo.fromPlayer(player));
        MessageUtil.sendSuccess(player, MODULE, "成功将当前位置设置为家 [" + key + "]！");
        return 1;
    }

    private static int tpHome(ServerPlayer player, String name) {
        PlayerProfileData prof = CmiManager.get().getOrCreateProfile(player);
        GlobalPosInfo pos = prof.homes.get(name.toLowerCase(Locale.ROOT));
        if (pos == null) {
            MessageUtil.sendError(player, MODULE, "未找到名为 [" + name + "] 的家，输入 /homes 查看列表。");
            return 0;
        }
        TeleportWarmupManager.get().scheduleTeleportToPos(player, "家 " + name, pos);
        return 1;
    }

    private static int setWarp(ServerPlayer player, String name, double cost) {
        String key = name.toLowerCase(Locale.ROOT);
        CmiManager.get().getWarps().put(key, new WarpPoint(name, GlobalPosInfo.fromPlayer(player), cost, player.getGameProfile().getName()));
        io.wifi.habitrainsurvive.data.ServerDataManager.saveAll();
        MessageUtil.sendSuccess(player, MODULE, "已设置地标 [" + name + "] (传送费: " + EconomyManager.get().format(cost) + ")。");
        return 1;
    }

    private static int sendTpa(ServerPlayer sender, ServerPlayer target, boolean tpaHere) {
        return sendTpa(sender, target, tpaHere, false);
    }

    private static boolean checkTpaCooldown(ServerPlayer sender) {
        long remaining = CmiManager.get().getTpaCooldownRemaining(sender.getUUID());
        if (remaining <= 0) return true;
        MessageUtil.sendError(sender, MODULE, "传送请求冷却中，请等待 " + remaining + " 秒后再发送。");
        return false;
    }

    private static int sendTpa(ServerPlayer sender, ServerPlayer target, boolean tpaHere, boolean batch) {
        if (sender.getUUID().equals(target.getUUID())) {
            MessageUtil.sendError(sender, MODULE, "不能向自己发送传送请求。");
            return 0;
        }
        PlayerProfileData targetProf = CmiManager.get().getOrCreateProfile(target);
        if (!targetProf.tpaToggle && !sender.hasPermissions(2)) {
            MessageUtil.sendError(sender, MODULE, "对方已开启 TPA 免打扰模式，暂不接收传送请求。");
            return 0;
        }
        if (targetProf.ignoredPlayers.contains(sender.getUUID()) && !sender.hasPermissions(2)) {
            MessageUtil.sendError(sender, MODULE, "对方已将您屏蔽，无法发送请求。");
            return 0;
        }
        if (!batch && !checkTpaCooldown(sender)) return 0;
        CmiManager.get().addTpaRequest(new TpaRequest(sender.getUUID(), target.getUUID(), tpaHere));
        if (!batch) CmiManager.get().markTpaSent(sender.getUUID());
        MessageUtil.sendSuccess(sender, MODULE, "已向 " + target.getName().getString() + " 发送传送请求 (60秒内有效，可输入 /tpcancel 取消)。");
        target.sendSystemMessage(MessageUtil.prefix(MODULE, ChatFormatting.AQUA)
                .append(Component.literal(sender.getName().getString() + (tpaHere ? " 请求您传送到他身边 " : " 请求传送到您身边 ")).withStyle(ChatFormatting.YELLOW))
                .append(MessageUtil.commandButton("接受", "/tpaccept", "点击接受传送", ChatFormatting.GREEN))
                .append(Component.literal(" "))
                .append(MessageUtil.commandButton("拒绝", "/tpdeny", "点击拒绝传送", ChatFormatting.RED)));
        MessageUtil.playDing(target);
        return 1;
    }

    private static int executeRtp(ServerPlayer player) {
        String id = player.serverLevel().dimension().location().toString();
        var definition = io.wifi.habitrainsurvive.world.WorldManager.get().definition(id);
        if (definition != null && "hub".equals(definition.kind)) id = "minecraft:overworld";
        return io.wifi.habitrainsurvive.world.WorldManager.get().rtp(player, id) ? 1 : 0;
    }

    private static int toggleFly(ServerPlayer executor, ServerPlayer target) {
        boolean next = !target.getAbilities().mayfly;
        target.getAbilities().mayfly = next;
        if (!next) target.getAbilities().flying = false;
        target.onUpdateAbilities();
        MessageUtil.sendSuccess(executor, MODULE, "玩家 " + target.getName().getString() + " 的飞行模式已" + (next ? "开启" : "关闭"));
        return 1;
    }

    private static int healPlayer(ServerPlayer target) {
        target.setHealth(target.getMaxHealth());
        target.getFoodData().setFoodLevel(20);
        target.getFoodData().setSaturation(20.0f);
        target.clearFire();
        MessageUtil.sendSuccess(target, MODULE, "您的生命值与饥饿值已完全恢复！");
        return 1;
    }

    private static int feedPlayer(ServerPlayer target) {
        target.getFoodData().setFoodLevel(20);
        target.getFoodData().setSaturation(20.0f);
        MessageUtil.sendSuccess(target, MODULE, "您的饥饿值已完全恢复！");
        return 1;
    }

    private static int sendPrivateMsg(ServerPlayer sender, ServerPlayer target, String msg) {
        PlayerProfileData targetProf = CmiManager.get().getOrCreateProfile(target);
        if (targetProf.ignoredPlayers.contains(sender.getUUID()) && !sender.hasPermissions(2)) {
            MessageUtil.sendError(sender, MODULE, "对方已屏蔽您的私聊消息。");
            return 0;
        }
        CmiManager.get().setLastMsgPartner(sender.getUUID(), target.getUUID());
        sender.sendSystemMessage(Component.literal("[私聊 -> " + target.getName().getString() + "] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(msg).withStyle(ChatFormatting.WHITE)));
        target.sendSystemMessage(Component.literal("[私聊 <- " + sender.getName().getString() + "] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(msg).withStyle(ChatFormatting.WHITE)));
        MessageUtil.playDing(target);
        return 1;
    }

    private static int listKits(ServerPlayer player) {
        var kits = CmiManager.get().getKits().values();
        if (kits.isEmpty()) {
            MessageUtil.sendInfo(player, MODULE, "服务器当前没有配置任何礼包。管理员可使用 /kit create 创建。");
            return 1;
        }
        player.sendSystemMessage(Component.literal("=== 可领取的服务器礼包 ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (KitData k : kits) {
            player.sendSystemMessage(Component.literal("- [" + k.name + "] ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("冷却: " + k.cooldownSeconds + "秒 | 费用: " + EconomyManager.get().format(k.cost) + " ").withStyle(ChatFormatting.WHITE))
                    .append(MessageUtil.commandButton("点击领取", "/kit claim " + k.name, "领取礼包 " + k.name, ChatFormatting.GREEN)));
        }
        return 1;
    }

    private static int claimKit(ServerPlayer player, String name) {
        KitData kit = CmiManager.get().getKits().get(name.toLowerCase(Locale.ROOT));
        if (kit == null) {
            MessageUtil.sendError(player, MODULE, "礼包 [" + name + "] 不存在。");
            return 0;
        }
        PlayerProfileData prof = CmiManager.get().getOrCreateProfile(player);
        long now = System.currentTimeMillis();
        long last = prof.kitCooldowns.getOrDefault(kit.name.toLowerCase(Locale.ROOT), 0L);
        if (now - last < kit.cooldownSeconds * 1000L && !player.hasPermissions(2)) {
            long rem = (kit.cooldownSeconds * 1000L - (now - last)) / 1000L;
            MessageUtil.sendError(player, MODULE, "该礼包仍在冷却中，剩余 " + rem + " 秒。");
            return 0;
        }
        if (kit.cost > 0 && !EconomyManager.get().withdraw(player.getUUID(), kit.cost, "Claim Kit " + kit.name)) {
            MessageUtil.sendError(player, MODULE, "领取该礼包需要 " + EconomyManager.get().format(kit.cost) + "，您的余额不足！");
            return 0;
        }
        prof.kitCooldowns.put(kit.name.toLowerCase(Locale.ROOT), now);
        MinecraftServer server = player.server;
        for (String json : kit.serializedItems) {
            ItemStack stack = ItemSerializer.deserialize(json, server.registryAccess());
            if (!stack.isEmpty()) {
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
        }
        MessageUtil.sendSuccess(player, MODULE, "已成功领取礼包 [" + kit.name + "]！");
        return 1;
    }

    private static int createKitFromInventory(ServerPlayer player, String name, long cooldown, double cost) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (!st.isEmpty()) {
                items.add(ItemSerializer.serialize(st, player.server.registryAccess()));
            }
        }
        if (items.isEmpty()) {
            MessageUtil.sendError(player, MODULE, "请将要放入礼包的物品放在快捷栏 (1~9号槽位) 中再执行此命令！");
            return 0;
        }
        CmiManager.get().getKits().put(name.toLowerCase(Locale.ROOT), new KitData(name, cooldown, cost, items));
        MessageUtil.sendSuccess(player, MODULE, "成功根据快捷栏物品创建礼包 [" + name + "] (包含 " + items.size() + " 组物品)！");
        return 1;
    }
}
