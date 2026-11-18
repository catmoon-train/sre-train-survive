package io.wifi.habitrainsurvive.town;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.UUID;

public class TownCommands {
    private static final String MODULE = "城镇";

    private static boolean admin(CommandSourceStack source) {
        return PermissionManager.get().hasPermission(source, "habi.admin", 2);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("town")
                .executes(ctx -> showMyTownInfo(ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("gui")
                        .executes(ctx -> {
                            TownGui.open(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("menu")
                        .executes(ctx -> {
                            TownGui.open(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> createTown(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("disband")
                        .executes(ctx -> disbandTown(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("info")
                        .executes(ctx -> showMyTownInfo(ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    Town t = TownManager.get().getTownByName(StringArgumentType.getString(ctx, "name"));
                                    if (t == null) {
                                        MessageUtil.sendError(p, MODULE, "未找到该城镇。");
                                        return 0;
                                    }
                                    return displayTown(p, t);
                                })))
                .then(Commands.literal("list")
                        .executes(ctx -> listTowns(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("spawn")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                            if (t == null) {
                                MessageUtil.sendError(p, MODULE, "您还未加入任何城镇！");
                                return 0;
                            }
                            if (t.spawnPos != null) {
                                TeleportWarmupManager.get().scheduleTeleportToPos(p, "城镇 " + t.name, t.spawnPos);
                            } else {
                                MessageUtil.sendError(p, MODULE, "该城镇尚未设置出生点。");
                                return 0;
                            }
                            return 1;
                        })
                        .then(Commands.argument("townName", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    Town t = TownManager.get().getTownByName(StringArgumentType.getString(ctx, "townName"));
                                    if (t == null) {
                                        MessageUtil.sendError(p, MODULE, "未找到该城镇。");
                                        return 0;
                                    }
                                    if (t.isOutlaw(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "您已被城镇 [" + t.name + "] 列入通缉名单，禁止传送前往！");
                                        return 0;
                                    }
                                    Town myTown = TownManager.get().getPlayerTown(p.getUUID());
                                    boolean isAlly = myTown != null && t.allies != null && t.allies.contains(myTown.id);
                                    if (!t.openToPublic && !t.isResident(p.getUUID()) && !isAlly) {
                                        MessageUtil.sendError(p, MODULE, "该城镇未对外开放公共传送（仅限本城或同盟城镇居民）。");
                                        return 0;
                                    }
                                    if (t.spawnPos != null) {
                                        TeleportWarmupManager.get().scheduleTeleportToPos(p, "城镇 " + t.name, t.spawnPos);
                                    } else {
                                        MessageUtil.sendError(p, MODULE, "该城镇尚未设置出生点。");
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("setspawn")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                            if (t == null || !t.isStaff(p.getUUID())) {
                                MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以设置城镇出生点。");
                                return 0;
                            }
                            t.spawnPos = GlobalPosInfo.fromPlayer(p);
                            MessageUtil.sendSuccess(p, MODULE, "已更新城镇 [" + t.name + "] 的出生点。");
                            return 1;
                        }))
                .then(Commands.literal("claim")
                        .executes(ctx -> claimTownChunk(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("unclaim")
                        .executes(ctx -> unclaimTownChunk(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("deposit")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    double amount = DoubleArgumentType.getDouble(ctx, "amount");
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null) {
                                        MessageUtil.sendError(p, MODULE, "您尚未加入城镇。");
                                        return 0;
                                    }
                                    if (!EconomyManager.get().withdraw(p.getUUID(), amount, "Town Deposit " + t.name)) {
                                        MessageUtil.sendError(p, MODULE, "您的个人余额不足！");
                                        return 0;
                                    }
                                    t.bankBalance += amount;
                                    MessageUtil.sendSuccess(p, MODULE, "已向城镇金库捐献 " + EconomyManager.get().format(amount) + "，金库总额: " + EconomyManager.get().format(t.bankBalance));
                                    return 1;
                                })))
                .then(Commands.literal("withdraw")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    double amount = DoubleArgumentType.getDouble(ctx, "amount");
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null || t.getRank(p.getUUID()) == null || t.getRank(p.getUUID()).getLevel() < TownRank.CO_MAYOR.getLevel()) {
                                        MessageUtil.sendError(p, MODULE, "只有镇长或副镇长可以从城镇金库取款！");
                                        return 0;
                                    }
                                    if (t.bankBalance < amount) {
                                        MessageUtil.sendError(p, MODULE, "城镇金库余额不足！");
                                        return 0;
                                    }
                                    t.bankBalance -= amount;
                                    EconomyManager.get().deposit(p.getUUID(), amount, "Town Withdraw " + t.name);
                                    MessageUtil.sendSuccess(p, MODULE, "已从城镇金库提取 " + EconomyManager.get().format(amount));
                                    return 1;
                                })))
                .then(Commands.literal("ally")
                        .then(Commands.literal("add")
                                .then(Commands.argument("otherTown", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                                            if (t == null || !t.isStaff(p.getUUID())) {
                                                MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以管理外交同盟。");
                                                return 0;
                                            }
                                            Town other = TownManager.get().getTownByName(StringArgumentType.getString(ctx, "otherTown"));
                                            if (other == null || other.id.equals(t.id)) {
                                                MessageUtil.sendError(p, MODULE, "目标城镇不存在！");
                                                return 0;
                                            }
                                            t.allies.add(other.id);
                                            MessageUtil.sendSuccess(p, MODULE, "已将城镇 [" + other.name + "] 设为友好同盟城镇！");
                                            return 1;
                                        })))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("otherTown", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                                            if (t == null || !t.isStaff(p.getUUID())) {
                                                MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以管理外交同盟。");
                                                return 0;
                                            }
                                            String other = StringArgumentType.getString(ctx, "otherTown").toLowerCase(Locale.ROOT);
                                            t.allies.remove(other);
                                            MessageUtil.sendSuccess(p, MODULE, "已解除与 [" + other + "] 的城镇同盟关系。");
                                            return 1;
                                        }))))
                .then(Commands.literal("outlaw")
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                                            if (t == null || !t.isStaff(p.getUUID())) {
                                                MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以添加通缉人员。");
                                                return 0;
                                            }
                                            if (target.getUUID().equals(t.mayorUuid)) {
                                                MessageUtil.sendError(p, MODULE, "不能通缉镇长！");
                                                return 0;
                                            }
                                            if (t.isResident(target.getUUID())) {
                                                t.removeResident(target.getUUID());
                                            }
                                            t.pendingInvites.remove(target.getUUID());
                                            t.outlaws.add(target.getUUID());
                                            t.outlawNames.put(target.getUUID(), target.getGameProfile().getName());
                                            MessageUtil.sendSuccess(p, MODULE, "已将 " + target.getName().getString() + " 列入城镇通缉黑名单。");
                                            MessageUtil.sendError(target, MODULE, "您已被城镇 [" + t.name + "] 列入通缉名单！");
                                            return 1;
                                        })))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("playerName", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            String name = StringArgumentType.getString(ctx, "playerName");
                                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                                            if (t == null || !t.isStaff(p.getUUID())) {
                                                MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以解除通缉。");
                                                return 0;
                                            }
                                            UUID targetUuid = null;
                                            ServerPlayer online = p.server.getPlayerList().getPlayerByName(name);
                                            if (online != null && t.outlaws.contains(online.getUUID())) {
                                                targetUuid = online.getUUID();
                                            } else if (t.outlawNames != null) {
                                                for (var entry : t.outlawNames.entrySet()) {
                                                    if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(name)) {
                                                        targetUuid = entry.getKey();
                                                        break;
                                                    }
                                                }
                                            }
                                            if (targetUuid == null) {
                                                MessageUtil.sendError(p, MODULE, "通缉名单中未找到该玩家。");
                                                return 0;
                                            }
                                            t.outlaws.remove(targetUuid);
                                            if (t.outlawNames != null) t.outlawNames.remove(targetUuid);
                                            MessageUtil.sendSuccess(p, MODULE, "已解除对 " + name + " 的城镇通缉。");
                                            return 1;
                                        })))
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null) {
                                        MessageUtil.sendError(p, MODULE, "您尚未加入城镇。");
                                        return 0;
                                    }
                                    if (t.outlaws.isEmpty()) {
                                        MessageUtil.sendInfo(p, MODULE, "城镇 [" + t.name + "] 当前没有通缉人员。");
                                        return 1;
                                    }
                                    StringBuilder sb = new StringBuilder();
                                    for (UUID uid : t.outlaws) {
                                        if (!sb.isEmpty()) sb.append(", ");
                                        sb.append(t.outlawNames != null ? t.outlawNames.getOrDefault(uid, uid.toString().substring(0, 8)) : uid.toString().substring(0, 8));
                                    }
                                    MessageUtil.sendInfo(p, MODULE, "通缉黑名单 (" + t.outlaws.size() + "): " + sb);
                                    return 1;
                                })))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null || !t.isStaff(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "您没有权限邀请玩家加入城镇。");
                                        return 0;
                                    }
                                    if (t.isOutlaw(target.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "该玩家处于城镇通缉名单中，请先解除通缉。");
                                        return 0;
                                    }
                                    t.pendingInvites.add(target.getUUID());
                                    MessageUtil.sendSuccess(p, MODULE, "已向 " + target.getName().getString() + " 发送城镇邀请。");
                                    target.sendSystemMessage(MessageUtil.prefix(MODULE, ChatFormatting.AQUA)
                                            .append(Component.literal(p.getName().getString() + " 邀请您加入城镇 [" + t.name + "] ").withStyle(ChatFormatting.YELLOW))
                                            .append(MessageUtil.commandButton("点击加入", "/town join " + t.name, "加入城镇 " + t.name, ChatFormatting.GREEN)));
                                    return 1;
                                })))
                .then(Commands.literal("join")
                        .then(Commands.argument("townName", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    if (TownManager.get().getPlayerTown(p.getUUID()) != null) {
                                        MessageUtil.sendError(p, MODULE, "您已经属于一个城镇，请先退出当前城镇 (/town leave)。");
                                        return 0;
                                    }
                                    Town t = TownManager.get().getTownByName(StringArgumentType.getString(ctx, "townName"));
                                    if (t == null) {
                                        MessageUtil.sendError(p, MODULE, "未找到该城镇。");
                                        return 0;
                                    }
                                    if (t.isOutlaw(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "您已被城镇 [" + t.name + "] 列入通缉黑名单，无法加入！");
                                        return 0;
                                    }
                                    if (!t.openToPublic && !t.pendingInvites.contains(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "该城镇需要邀请才能加入！");
                                        return 0;
                                    }
                                    t.pendingInvites.remove(p.getUUID());
                                    t.addResident(p.getUUID(), p.getGameProfile().getName(), TownRank.RESIDENT);
                                    MessageUtil.sendSuccess(p, MODULE, "恭喜！您已成功加入城镇 [" + t.name + "]！");
                                    return 1;
                                })))
                .then(Commands.literal("leave")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                            if (t == null) {
                                MessageUtil.sendError(p, MODULE, "您当前不在任何城镇中。");
                                return 0;
                            }
                            if (t.mayorUuid.equals(p.getUUID())) {
                                MessageUtil.sendError(p, MODULE, "镇长不能直接离开城镇，请先使用 /town setmayor <玩家> 转让镇长或 /town disband 解散城镇。");
                                return 0;
                            }
                            t.removeResident(p.getUUID());
                            MessageUtil.sendSuccess(p, MODULE, "您已离开城镇 [" + t.name + "]，名下城镇地块已自动归还。");
                            return 1;
                        }))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    String targetName = StringArgumentType.getString(ctx, "player");
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null || !t.isStaff(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "您无权踢出居民。");
                                        return 0;
                                    }
                                    UUID targetUuid = TownManager.get().resolveResidentUuid(t, targetName, p.server);
                                    if (targetUuid == null) {
                                        MessageUtil.sendError(p, MODULE, "未在本城镇居民中找到玩家: " + targetName);
                                        return 0;
                                    }
                                    if (targetUuid.equals(t.mayorUuid)) {
                                        MessageUtil.sendError(p, MODULE, "不能踢出镇长！");
                                        return 0;
                                    }
                                    String resolvedName = t.getResidentName(targetUuid);
                                    t.removeResident(targetUuid);
                                    MessageUtil.sendSuccess(p, MODULE, "已将 " + resolvedName + " 移出城镇并收回其地块。");
                                    ServerPlayer onlineTarget = p.server.getPlayerList().getPlayer(targetUuid);
                                    if (onlineTarget != null) {
                                        MessageUtil.sendError(onlineTarget, MODULE, "您已被移出城镇 [" + t.name + "]。");
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("promote")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("rank", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(new String[]{"CO_MAYOR", "ASSISTANT", "RESIDENT"}, b))
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            String targetName = StringArgumentType.getString(ctx, "player");
                                            String rankStr = StringArgumentType.getString(ctx, "rank").toUpperCase(Locale.ROOT);
                                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                                            if (t == null || !t.mayorUuid.equals(p.getUUID())) {
                                                MessageUtil.sendError(p, MODULE, "只有镇长可以调整官员职级！");
                                                return 0;
                                            }
                                            UUID targetUuid = TownManager.get().resolveResidentUuid(t, targetName, p.server);
                                            if (targetUuid == null) {
                                                MessageUtil.sendError(p, MODULE, "该玩家不是本城镇居民。");
                                                return 0;
                                            }
                                            if (targetUuid.equals(t.mayorUuid)) {
                                                MessageUtil.sendError(p, MODULE, "不能调整镇长自身职级，请使用 /town setmayor 转让镇长。");
                                                return 0;
                                            }
                                            TownRank rank;
                                            try {
                                                rank = TownRank.valueOf(rankStr);
                                            } catch (IllegalArgumentException e) {
                                                MessageUtil.sendError(p, MODULE, "可用职级: CO_MAYOR, ASSISTANT, RESIDENT");
                                                return 0;
                                            }
                                            if (rank == TownRank.MAYOR) {
                                                MessageUtil.sendError(p, MODULE, "转让镇长请使用 /town setmayor <玩家>。");
                                                return 0;
                                            }
                                            t.residents.put(targetUuid, rank);
                                            MessageUtil.sendSuccess(p, MODULE, "已将 " + t.getResidentName(targetUuid) + " 任命为 " + rank.getDisplayName());
                                            return 1;
                                        }))))
                .then(Commands.literal("setmayor")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    String targetName = StringArgumentType.getString(ctx, "player");
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null || !t.mayorUuid.equals(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "只有当前镇长可以转让镇长职位！");
                                        return 0;
                                    }
                                    UUID targetUuid = TownManager.get().resolveResidentUuid(t, targetName, p.server);
                                    if (targetUuid == null || targetUuid.equals(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "目标必须是本城镇的其他居民。");
                                        return 0;
                                    }
                                    ServerPlayer online = p.server.getPlayerList().getPlayer(targetUuid);
                                    String resolvedName = online != null ? online.getGameProfile().getName() : t.getResidentName(targetUuid);
                                    t.transferMayor(targetUuid, resolvedName);
                                    MessageUtil.sendSuccess(p, MODULE, "已将城镇 [" + t.name + "] 的镇长职位转让给 " + resolvedName + "（您现在为副镇长）。");
                                    if (online != null) {
                                        MessageUtil.sendSuccess(online, MODULE, "您已成为城镇 [" + t.name + "] 的新任镇长！");
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("board")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null || !t.isStaff(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "您无权修改城镇公告。");
                                        return 0;
                                    }
                                    t.boardMessage = StringArgumentType.getString(ctx, "message");
                                    MessageUtil.sendSuccess(p, MODULE, "城镇公告已更新为: " + t.boardMessage);
                                    return 1;
                                })))
                .then(Commands.literal("tax")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0, 10000.0))
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null || !t.mayorUuid.equals(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "只有镇长可以设置每日税收。");
                                        return 0;
                                    }
                                    t.dailyTax = DoubleArgumentType.getDouble(ctx, "amount");
                                    MessageUtil.sendSuccess(p, MODULE, "已将城镇每日居民税设为 " + EconomyManager.get().format(t.dailyTax));
                                    return 1;
                                })))
                .then(Commands.literal("toggle")
                        .then(Commands.literal("pvp")
                                .executes(ctx -> toggleSetting(ctx.getSource().getPlayerOrException(), "pvp")))
                        .then(Commands.literal("explosion")
                                .executes(ctx -> toggleSetting(ctx.getSource().getPlayerOrException(), "explosion")))
                        .then(Commands.literal("open")
                                .executes(ctx -> toggleSetting(ctx.getSource().getPlayerOrException(), "open")))
                        .then(Commands.literal("fire")
                                .executes(ctx -> toggleSetting(ctx.getSource().getPlayerOrException(), "fire")))
                        .then(Commands.literal("residentbuild")
                                .executes(ctx -> toggleSetting(ctx.getSource().getPlayerOrException(), "residentbuild")))
                        .then(Commands.literal("interact")
                                .executes(ctx -> toggleSetting(ctx.getSource().getPlayerOrException(), "interact")))
                        .then(Commands.literal("friendlyfire")
                                .executes(ctx -> toggleSetting(ctx.getSource().getPlayerOrException(), "friendlyfire"))))
                .then(Commands.literal("plot")
                        .then(Commands.literal("info")
                                .executes(ctx -> plotInfo(ctx.getSource().getPlayerOrException())))
                        .then(Commands.literal("forsale")
                                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.0))
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                                            if (t == null || !t.isStaff(p.getUUID())) {
                                                MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以挂牌出售城镇区块地块。");
                                                return 0;
                                            }
                                            String key = Town.chunkKeyAt(p.serverLevel().dimension().location().toString(), p.blockPosition());
                                            TownPlot plot = t.claimedChunks.get(key);
                                            if (plot == null) {
                                                MessageUtil.sendError(p, MODULE, "当前区块不属于您的城镇。");
                                                return 0;
                                            }
                                            plot.forSale = true;
                                            plot.salePrice = DoubleArgumentType.getDouble(ctx, "price");
                                            MessageUtil.sendSuccess(p, MODULE, "已将当前区块地块挂牌出售，价格: " + EconomyManager.get().format(plot.salePrice));
                                            return 1;
                                        })))
                        .then(Commands.literal("notforsale")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null || !t.isStaff(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以取消地块出售。");
                                        return 0;
                                    }
                                    String key = Town.chunkKeyAt(p.serverLevel().dimension().location().toString(), p.blockPosition());
                                    TownPlot plot = t.claimedChunks.get(key);
                                    if (plot == null) {
                                        MessageUtil.sendError(p, MODULE, "当前区块不属于您的城镇。");
                                        return 0;
                                    }
                                    plot.forSale = false;
                                    plot.salePrice = 0.0;
                                    MessageUtil.sendSuccess(p, MODULE, "已取消当前区块地块的出售挂牌。");
                                    return 1;
                                }))
                        .then(Commands.literal("evict")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null || !t.isStaff(p.getUUID())) {
                                        MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以收回地块。");
                                        return 0;
                                    }
                                    String key = Town.chunkKeyAt(p.serverLevel().dimension().location().toString(), p.blockPosition());
                                    TownPlot plot = t.claimedChunks.get(key);
                                    if (plot == null || plot.ownerUuid == null) {
                                        MessageUtil.sendError(p, MODULE, "当前区块没有个人所有者。");
                                        return 0;
                                    }
                                    plot.clearOwner();
                                    MessageUtil.sendSuccess(p, MODULE, "已收回当前区块地块，现归城镇公共所有。");
                                    return 1;
                                }))
                        .then(Commands.literal("trust")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> plotTrust(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"), true))))
                        .then(Commands.literal("untrust")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> plotTrust(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"), false))))
                        .then(Commands.literal("buy")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    Town t = TownManager.get().getPlayerTown(p.getUUID());
                                    if (t == null) {
                                        MessageUtil.sendError(p, MODULE, "您必须是本城镇居民才能购买城镇内部地块。");
                                        return 0;
                                    }
                                    String key = Town.chunkKeyAt(p.serverLevel().dimension().location().toString(), p.blockPosition());
                                    TownPlot plot = t.claimedChunks.get(key);
                                    if (plot == null || !plot.forSale) {
                                        MessageUtil.sendError(p, MODULE, "当前区块未在挂牌出售中。");
                                        return 0;
                                    }
                                    if (!EconomyManager.get().withdraw(p.getUUID(), plot.salePrice, "Buy Town Plot")) {
                                        MessageUtil.sendError(p, MODULE, "余额不足，需要 " + EconomyManager.get().format(plot.salePrice));
                                        return 0;
                                    }
                                    t.bankBalance += plot.salePrice;
                                    plot.forSale = false;
                                    plot.ownerUuid = p.getUUID();
                                    plot.ownerName = p.getGameProfile().getName();
                                    MessageUtil.sendSuccess(p, MODULE, "成功购买该城镇区块地块！现在仅您与受信任成员可在此区块内建造。");
                                    return 1;
                                })))
                .then(Commands.literal("admin").requires(TownCommands::admin)
                        .then(Commands.literal("list")
                                .executes(ctx -> listTowns(ctx.getSource().getPlayerOrException())))
                        .then(Commands.literal("disband")
                                .then(Commands.argument("townName", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "townName");
                                            Town removed = TownManager.get().removeTown(name);
                                            if (removed == null) {
                                                ctx.getSource().sendFailure(Component.literal("未找到城镇: " + name));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal("管理员已强制解散城镇 [" + removed.name + "]"), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("config")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(new String[]{"createCost", "claimCost", "upkeep"}, b))
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0))
                                                .executes(ctx -> {
                                                    String key = StringArgumentType.getString(ctx, "key").toLowerCase(Locale.ROOT);
                                                    double value = DoubleArgumentType.getDouble(ctx, "value");
                                                    switch (key) {
                                                        case "createcost" -> TownManager.get().setTownCreateCost(value);
                                                        case "claimcost" -> TownManager.get().setChunkClaimCost(value);
                                                        case "upkeep" -> TownManager.get().setDailyUpkeepPerChunk(value);
                                                        default -> {
                                                            ctx.getSource().sendFailure(Component.literal("未知配置项，可选: createCost, claimCost, upkeep"));
                                                            return 0;
                                                        }
                                                    }
                                                    ctx.getSource().sendSuccess(() -> Component.literal("已更新城镇配置 " + key + " = " + value), true);
                                                    return 1;
                                                }))))));

        // 城镇聊天 /tc <message>
        dispatcher.register(Commands.literal("tc")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            Town t = TownManager.get().getPlayerTown(p.getUUID());
                            if (t == null) {
                                MessageUtil.sendError(p, MODULE, "您尚未加入任何城镇，无法使用城镇频道。");
                                return 0;
                            }
                            String msg = StringArgumentType.getString(ctx, "message");
                            TownRank rank = t.getRank(p.getUUID());
                            Component chat = Component.literal("[" + t.name + "|" + (rank != null ? rank.getDisplayName() : "居民") + "] ")
                                    .withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(p.getName().getString() + ": ").withStyle(ChatFormatting.WHITE))
                                    .append(Component.literal(msg).withStyle(ChatFormatting.GREEN));
                            for (ServerPlayer online : p.server.getPlayerList().getPlayers()) {
                                if (t.isResident(online.getUUID())) {
                                    online.sendSystemMessage(chat);
                                }
                            }
                            return 1;
                        })));
    }

    private static int toggleSetting(ServerPlayer p, String setting) {
        Town t = TownManager.get().getPlayerTown(p.getUUID());
        if (t == null || !t.isStaff(p.getUUID())) {
            MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以切换城镇设置。");
            return 0;
        }
        switch (setting) {
            case "pvp" -> {
                t.pvpEnabled = !t.pvpEnabled;
                MessageUtil.sendSuccess(p, MODULE, "城镇 PVP 状态已切换为: " + (t.pvpEnabled ? "开启" : "关闭"));
            }
            case "explosion" -> {
                t.explosionEnabled = !t.explosionEnabled;
                MessageUtil.sendSuccess(p, MODULE, "城镇爆炸破坏已切换为: " + (t.explosionEnabled ? "允许" : "免疫保护"));
            }
            case "open" -> {
                t.openToPublic = !t.openToPublic;
                MessageUtil.sendSuccess(p, MODULE, "城镇自由加入与公共传送已切换为: " + (t.openToPublic ? "开放" : "仅限受邀"));
            }
            case "fire" -> {
                t.fireSpreadEnabled = !t.fireSpreadEnabled;
                MessageUtil.sendSuccess(p, MODULE, "城镇火焰蔓延已切换为: " + (t.fireSpreadEnabled ? "允许" : "免疫保护"));
            }
            case "residentbuild" -> {
                t.allowAllResidentBuild = !t.allowAllResidentBuild;
                MessageUtil.sendSuccess(p, MODULE, "全体居民公共建造已切换为: " + (t.allowAllResidentBuild ? "允许" : "禁止"));
            }
            case "interact" -> {
                t.publicInteract = !t.publicInteract;
                MessageUtil.sendSuccess(p, MODULE, "访客公共交互已切换为: " + (t.publicInteract ? "允许" : "禁止"));
            }
            case "friendlyfire" -> {
                t.friendlyFire = !t.friendlyFire;
                MessageUtil.sendSuccess(p, MODULE, "同城/同盟友军伤害已切换为: " + (t.friendlyFire ? "允许" : "关闭"));
            }
            default -> {
                return 0;
            }
        }
        return 1;
    }

    private static int plotInfo(ServerPlayer p) {
        String dim = p.serverLevel().dimension().location().toString();
        Town t = TownManager.get().getTownAt(dim, p.blockPosition());
        if (t == null) {
            MessageUtil.sendInfo(p, MODULE, "当前区块为无主荒野，不属于任何城镇。");
            return 1;
        }
        TownPlot plot = t.claimedChunks.get(Town.chunkKeyAt(dim, p.blockPosition()));
        String owner = (plot != null && plot.ownerUuid != null) ? t.getResidentName(plot.ownerUuid) : "城镇公共";
        String sale = (plot != null && plot.forSale) ? "出售中 (" + EconomyManager.get().format(plot.salePrice) + ")" : "未出售";
        MessageUtil.sendInfo(p, MODULE, "当前区块属于城镇 [" + t.name + "] | 所有者: " + owner + " | 状态: " + sale);
        return 1;
    }

    private static int plotTrust(ServerPlayer p, ServerPlayer target, boolean add) {
        String dim = p.serverLevel().dimension().location().toString();
        Town t = TownManager.get().getTownAt(dim, p.blockPosition());
        if (t == null) {
            MessageUtil.sendError(p, MODULE, "当前区块不属于任何城镇。");
            return 0;
        }
        TownPlot plot = t.claimedChunks.get(Town.chunkKeyAt(dim, p.blockPosition()));
        if (plot == null || (!p.getUUID().equals(plot.ownerUuid) && !t.isStaff(p.getUUID()))) {
            MessageUtil.sendError(p, MODULE, "只有地块所有者或城镇管理人员可以修改地块信任名单。");
            return 0;
        }
        if (plot.trusted == null) plot.trusted = new java.util.HashSet<>();
        if (add) {
            plot.trusted.add(target.getUUID());
            MessageUtil.sendSuccess(p, MODULE, "已将 " + target.getName().getString() + " 添加到当前地块的信任名单。");
        } else {
            plot.trusted.remove(target.getUUID());
            MessageUtil.sendSuccess(p, MODULE, "已将 " + target.getName().getString() + " 从当前地块的信任名单移除。");
        }
        return 1;
    }

    private static int createTown(ServerPlayer player, String name) {
        if (TownManager.get().getPlayerTown(player.getUUID()) != null) {
            MessageUtil.sendError(player, MODULE, "您已经加入了一个城镇，不能重复建城！");
            return 0;
        }
        if (TownManager.get().getTownByName(name) != null) {
            MessageUtil.sendError(player, MODULE, "城镇名称 [" + name + "] 已存在！");
            return 0;
        }
        String dim = player.serverLevel().dimension().location().toString();
        if (!io.wifi.habitrainsurvive.world.WorldManager.get().assetAllowed(player, dim, false)) return 0;
        if (TownManager.get().getTownAt(dim, player.blockPosition()) != null) {
            MessageUtil.sendError(player, MODULE, "当前所在区块已被其他城镇占领！");
            return 0;
        }
        double cost = TownManager.get().getTownCreateCost();
        if (!EconomyManager.get().withdraw(player.getUUID(), cost, "Create Town " + name)) {
            MessageUtil.sendError(player, MODULE, "创建城镇需要 " + EconomyManager.get().format(cost) + "，您的余额不足！");
            return 0;
        }
        Town town = new Town(name.toLowerCase(Locale.ROOT), name, player.getUUID(), player.getGameProfile().getName(), GlobalPosInfo.fromPlayer(player));
        String chunkKey = Town.chunkKeyAt(dim, player.blockPosition());
        town.claimedChunks.put(chunkKey, new TownPlot(chunkKey));
        TownManager.get().registerTown(town);
        player.server.getPlayerList().broadcastSystemMessage(
                MessageUtil.prefix(MODULE, ChatFormatting.GOLD)
                        .append(Component.literal("玩家 " + player.getName().getString() + " 建立了全新的城镇 [" + name + "]！").withStyle(ChatFormatting.YELLOW)),
                false);
        return 1;
    }

    private static int disbandTown(ServerPlayer player) {
        Town t = TownManager.get().getPlayerTown(player.getUUID());
        if (t == null || !t.mayorUuid.equals(player.getUUID())) {
            MessageUtil.sendError(player, MODULE, "只有镇长可以解散城镇。");
            return 0;
        }
        TownManager.get().removeTown(t.id);
        if (t.bankBalance > 0) {
            EconomyManager.get().deposit(player.getUUID(), t.bankBalance, "Disband Town Bank Return");
        }
        MessageUtil.sendSuccess(player, MODULE, "城镇 [" + t.name + "] 已解散，金库剩余资金已退回您的账户。");
        return 1;
    }

    private static int claimTownChunk(ServerPlayer player) {
        Town t = TownManager.get().getPlayerTown(player.getUUID());
        if (t == null || !t.isStaff(player.getUUID())) {
            MessageUtil.sendError(player, MODULE, "只有城镇管理人员可以扩张城镇区块。");
            return 0;
        }
        int maxChunks = TownManager.get().getMaxChunksFor(t);
        if (t.claimedChunks.size() >= maxChunks) {
            MessageUtil.sendError(player, MODULE, "城镇区块数量已达上限 (" + t.claimedChunks.size() + "/" + maxChunks + ")，招募更多居民可提升上限！");
            return 0;
        }
        String dim = player.serverLevel().dimension().location().toString();
        if (!io.wifi.habitrainsurvive.world.WorldManager.get().assetAllowed(player, dim, false)) return 0;
        Town existing = TownManager.get().getTownAt(dim, player.blockPosition());
        if (existing != null) {
            MessageUtil.sendError(player, MODULE, "当前区块已被城镇 [" + existing.name + "] 占领！");
            return 0;
        }
        double cost = TownManager.get().getChunkClaimCost();
        if (t.bankBalance < cost) {
            MessageUtil.sendError(player, MODULE, "城镇金库余额不足！扩张一个区块需要金库资金 " + EconomyManager.get().format(cost) + " (使用 /town deposit 存入)。");
            return 0;
        }
        t.bankBalance -= cost;
        String key = Town.chunkKeyAt(dim, player.blockPosition());
        t.claimedChunks.put(key, new TownPlot(key));
        MessageUtil.sendSuccess(player, MODULE, "成功为城镇 [" + t.name + "] 占领区块 (" + (player.blockPosition().getX() >> 4) + ", " + (player.blockPosition().getZ() >> 4) + ")！"
                + " (" + t.claimedChunks.size() + "/" + maxChunks + ")");
        return 1;
    }

    private static int unclaimTownChunk(ServerPlayer player) {
        Town t = TownManager.get().getPlayerTown(player.getUUID());
        if (t == null || !t.isStaff(player.getUUID())) {
            MessageUtil.sendError(player, MODULE, "只有城镇管理人员可以放弃城镇区块。");
            return 0;
        }
        if (t.claimedChunks.size() <= 1) {
            MessageUtil.sendError(player, MODULE, "不能放弃城镇最后一个区块，如需解散请使用 /town disband。");
            return 0;
        }
        String key = Town.chunkKeyAt(player.serverLevel().dimension().location().toString(), player.blockPosition());
        if (!t.claimedChunks.containsKey(key)) {
            MessageUtil.sendError(player, MODULE, "当前区块不属于您的城镇。");
            return 0;
        }
        t.claimedChunks.remove(key);
        MessageUtil.sendSuccess(player, MODULE, "已放弃当前区块的城镇主权。");
        return 1;
    }

    private static int showMyTownInfo(ServerPlayer player) {
        Town t = TownManager.get().getPlayerTown(player.getUUID());
        if (t == null) {
            MessageUtil.sendInfo(player, MODULE, "您尚未加入任何城镇。使用 /town list 查看城镇列表，/town gui 打开面板或 /town create <名称> 创建。");
            return 0;
        }
        return displayTown(player, t);
    }

    private static int displayTown(ServerPlayer player, Town t) {
        int maxChunks = TownManager.get().getMaxChunksFor(t);
        double upkeep = Math.max(1, t.claimedChunks.size()) * TownManager.get().getDailyUpkeepPerChunk();
        player.sendSystemMessage(Component.literal("=== 城镇信息: [" + t.name + "] ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" "))
                .append(MessageUtil.commandButton("[打开面板]", "/town gui", "打开城镇可视化管理面板", ChatFormatting.GREEN)));
        player.sendSystemMessage(Component.literal("公告: " + t.boardMessage).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.literal("镇长: " + t.mayorName + " | 居民数: " + t.residents.size() + " | 领土区块: " + t.claimedChunks.size() + "/" + maxChunks).withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal("金库余额: " + EconomyManager.get().format(t.bankBalance)
                + " | 每日税率: " + EconomyManager.get().format(t.dailyTax)
                + " | 每日维护: " + EconomyManager.get().format(upkeep)).withStyle(ChatFormatting.GREEN));
        player.sendSystemMessage(Component.literal("对外开放: " + (t.openToPublic ? "是" : "否")
                + " | PVP: " + (t.pvpEnabled ? "允许" : "禁止")
                + " | 爆炸保护: " + (t.explosionEnabled ? "关闭" : "开启")
                + " | 火焰保护: " + (t.fireSpreadEnabled ? "关闭" : "开启")
                + " | 盟友: " + t.allies).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int listTowns(ServerPlayer player) {
        var all = TownManager.get().getAllTowns();
        if (all.isEmpty()) {
            MessageUtil.sendInfo(player, MODULE, "服务器当前还没有任何城镇。");
            return 1;
        }
        player.sendSystemMessage(Component.literal("=== 服务器城镇列表 (" + all.size() + ") ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (Town t : all) {
            player.sendSystemMessage(Component.literal("- [" + t.name + "] ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("镇长: " + t.mayorName + " | 居民: " + t.residents.size() + " | 区块: " + t.claimedChunks.size() + " ").withStyle(ChatFormatting.WHITE))
                    .append(MessageUtil.commandButton("详情", "/town info " + t.name, "查看城镇详情", ChatFormatting.YELLOW)));
        }
        return 1;
    }
}
