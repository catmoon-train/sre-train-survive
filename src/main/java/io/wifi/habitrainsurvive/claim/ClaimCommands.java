package io.wifi.habitrainsurvive.claim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

public class ClaimCommands {
    private static final String MODULE = "领地";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"claim", "res"}) {
            dispatcher.register(Commands.literal(root)
                    .executes(ctx -> {
                        ClaimControlPanelGui.openMainSelector(ctx.getSource().getPlayerOrException());
                        return 1;
                    })
                    .then(Commands.literal("gui")
                            .executes(ctx -> {
                                ClaimControlPanelGui.openMainSelector(ctx.getSource().getPlayerOrException());
                                return 1;
                            })
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null) {
                                            MessageUtil.sendError(p, MODULE, "未找到该领地。");
                                            return 0;
                                        }
                                        ClaimControlPanelGui.openClaimDashboard(p, c);
                                        return 1;
                                    })))
                    .then(Commands.literal("menu")
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                Claim standing = ClaimManager.get().getClaimAt(p.serverLevel().dimension().location().toString(), p.blockPosition());
                                if (standing != null) {
                                    ClaimControlPanelGui.openClaimDashboard(p, standing);
                                } else {
                                    ClaimControlPanelGui.openMainSelector(p);
                                }
                                return 1;
                            }))
                    .then(Commands.literal("wand")
                            .executes(ctx -> {
                                ClaimManager.get().giveClaimWand(ctx.getSource().getPlayerOrException());
                                return 1;
                            }))
                    .then(Commands.literal("pos1")
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                ClaimManager.get().setPos1(p, p.blockPosition());
                                MessageUtil.sendSuccess(p, MODULE, "已将当前坐标设为领地对角点 #1: " + p.blockPosition().toShortString());
                                return 1;
                            }))
                    .then(Commands.literal("pos2")
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                ClaimManager.get().setPos2(p, p.blockPosition());
                                MessageUtil.sendSuccess(p, MODULE, "已将当前坐标设为领地对角点 #2: " + p.blockPosition().toShortString());
                                return 1;
                            }))
                    .then(Commands.literal("create")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> createClaim(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")))))
                    .then(Commands.literal("subcreate")
                            .then(Commands.argument("parentClaim", StringArgumentType.word())
                                    .then(Commands.argument("subName", StringArgumentType.word())
                                            .executes(ctx -> createSubClaim(
                                                    ctx.getSource().getPlayerOrException(),
                                                    StringArgumentType.getString(ctx, "parentClaim"),
                                                    StringArgumentType.getString(ctx, "subName")
                                            )))))
                    .then(Commands.literal("delete")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> deleteClaim(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")))))
                    .then(Commands.literal("list")
                            .executes(ctx -> listClaims(ctx.getSource().getPlayerOrException())))
                    .then(Commands.literal("visualize")
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                Claim c = ClaimManager.get().getClaimAt(p.serverLevel().dimension().location().toString(), p.blockPosition());
                                if (c == null) {
                                    MessageUtil.sendError(p, MODULE, "请站在一个领地内或指定领地名 (/claim visualize <名称>)！");
                                    return 0;
                                }
                                ClaimManager.get().startVisualization(p, c, 15);
                                MessageUtil.sendSuccess(p, MODULE, "已开启领地 [" + c.name + "] 粒子边界高亮显示，持续 15 秒。");
                                return 1;
                            })
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null) {
                                            MessageUtil.sendError(p, MODULE, "未找到该领地。");
                                            return 0;
                                        }
                                        ClaimManager.get().startVisualization(p, c, 15);
                                        MessageUtil.sendSuccess(p, MODULE, "已开启领地 [" + c.name + "] 粒子边界高亮显示，持续 15 秒。");
                                        return 1;
                                    })))
                    .then(Commands.literal("info")
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                Claim c = ClaimManager.get().getClaimAt(p.serverLevel().dimension().location().toString(), p.blockPosition());
                                if (c == null) {
                                    MessageUtil.sendInfo(p, MODULE, "当前位置处于荒野（无私人领地）。");
                                    return 0;
                                }
                                return showClaimInfo(p, c);
                            })
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null) {
                                            MessageUtil.sendError(p, MODULE, "未找到该名称的领地。");
                                            return 0;
                                        }
                                        return showClaimInfo(p, c);
                                    })))
                    .then(Commands.literal("tp")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null) {
                                            MessageUtil.sendError(p, MODULE, "未找到领地。");
                                            return 0;
                                        }
                                        if (!c.getFlag(ClaimFlag.TELEPORT) && !c.isTrustedMember(p.getUUID()) && !p.hasPermissions(2)) {
                                            MessageUtil.sendError(p, MODULE, "该领地未对访客开放公共传送权限。");
                                            return 0;
                                        }
                                        if (c.teleportPos != null) {
                                            TeleportWarmupManager.get().scheduleTeleportToPos(p, "领地 " + c.name, c.teleportPos);
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("settp")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null || (!c.canManage(p.getUUID()) && !p.hasPermissions(2))) {
                                            MessageUtil.sendError(p, MODULE, "未找到领地或您没有管理权限。");
                                            return 0;
                                        }
                                        c.teleportPos = GlobalPosInfo.fromPlayer(p);
                                        MessageUtil.sendSuccess(p, MODULE, "已更新领地 [" + c.name + "] 的传送点为当前位置。");
                                        return 1;
                                    })))
                    // 领地邀请系统: /claim invite, /claim accept, /claim deny, /claim leave
                    .then(Commands.literal("invite")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> sendClaimInvite(
                                                    ctx.getSource().getPlayerOrException(),
                                                    StringArgumentType.getString(ctx, "name"),
                                                    EntityArgument.getPlayer(ctx, "player"),
                                                    "TRUSTED"
                                            ))
                                            .then(Commands.argument("role", StringArgumentType.word())
                                                    .executes(ctx -> sendClaimInvite(
                                                            ctx.getSource().getPlayerOrException(),
                                                            StringArgumentType.getString(ctx, "name"),
                                                            EntityArgument.getPlayer(ctx, "player"),
                                                            StringArgumentType.getString(ctx, "role")
                                                    ))))))
                    .then(Commands.literal("accept")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null || !c.pendingInvites.containsKey(p.getUUID())) {
                                            MessageUtil.sendError(p, MODULE, "未找到来自该领地的待处理邀请。");
                                            return 0;
                                        }
                                        ClaimRole role = c.pendingInvites.remove(p.getUUID());
                                        c.members.put(p.getUUID(), role);
                                        c.memberNames.put(p.getUUID(), p.getGameProfile().getName());
                                        MessageUtil.sendSuccess(p, MODULE, "您已接受邀请，正式成为领地 [" + c.name + "] 的 " + role.name() + " 成员！");
                                        ServerPlayer owner = p.server.getPlayerList().getPlayer(c.ownerUuid);
                                        if (owner != null) {
                                            MessageUtil.sendSuccess(owner, MODULE, p.getName().getString() + " 已接受您的邀请加入领地 [" + c.name + "]！");
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("deny")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null || c.pendingInvites.remove(p.getUUID()) == null) {
                                            MessageUtil.sendError(p, MODULE, "未找到来自该领地的邀请。");
                                            return 0;
                                        }
                                        MessageUtil.sendInfo(p, MODULE, "已拒绝领地 [" + c.name + "] 的成员邀请。");
                                        return 1;
                                    })))
                    .then(Commands.literal("leave")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null || !c.members.containsKey(p.getUUID())) {
                                            MessageUtil.sendError(p, MODULE, "您不是该领地的受信任成员。");
                                            return 0;
                                        }
                                        c.members.remove(p.getUUID());
                                        MessageUtil.sendSuccess(p, MODULE, "您已主动退出领地 [" + c.name + "] 的成员名单。");
                                        return 1;
                                    })))
                    // 领地挂牌出售与购买: /claim forsale, /claim notforsale, /claim buy
                    .then(Commands.literal("forsale")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("price", DoubleArgumentType.doubleArg(1.0))
                                            .executes(ctx -> {
                                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                                if (c == null || !c.ownerUuid.equals(p.getUUID())) {
                                                    MessageUtil.sendError(p, MODULE, "只有领主可以将领地挂牌出售。");
                                                    return 0;
                                                }
                                                c.forSale = true;
                                                c.salePrice = DoubleArgumentType.getDouble(ctx, "price");
                                                MessageUtil.sendSuccess(p, MODULE, "已将领地 [" + c.name + "] 挂牌至全服市场出售，标价: " + EconomyManager.get().format(c.salePrice));
                                                return 1;
                                            }))))
                    .then(Commands.literal("notforsale")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null || !c.ownerUuid.equals(p.getUUID())) return 0;
                                        c.forSale = false;
                                        MessageUtil.sendSuccess(p, MODULE, "已取消领地 [" + c.name + "] 的挂牌出售状态。");
                                        return 1;
                                    })))
                    .then(Commands.literal("buy")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        ServerPlayer buyer = ctx.getSource().getPlayerOrException();
                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                        if (c == null || !c.forSale) {
                                            MessageUtil.sendError(buyer, MODULE, "该领地未处于挂牌出售状态。");
                                            return 0;
                                        }
                                        if (c.ownerUuid.equals(buyer.getUUID())) {
                                            MessageUtil.sendError(buyer, MODULE, "不能购买自己的领地！");
                                            return 0;
                                        }
                                        if (!EconomyManager.get().transfer(buyer.getUUID(), c.ownerUuid, c.salePrice, "Buy Claim " + c.name)) {
                                            MessageUtil.sendError(buyer, MODULE, "您的金币不足以支付领地转让费 " + EconomyManager.get().format(c.salePrice));
                                            return 0;
                                        }
                                        c.forSale = false;
                                        c.ownerUuid = buyer.getUUID();
                                        c.ownerName = buyer.getGameProfile().getName();
                                        MessageUtil.sendSuccess(buyer, MODULE, "🎉 恭喜您成功以 " + EconomyManager.get().format(c.salePrice) + " 购得领地 [" + c.name + "] 的完整所有权！");
                                        MessageUtil.playDing(buyer);
                                        return 1;
                                    })))
                    .then(Commands.literal("transfer")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("newOwner", EntityArgument.player())
                                            .executes(ctx -> {
                                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                                ServerPlayer target = EntityArgument.getPlayer(ctx, "newOwner");
                                                if (c == null || (!c.ownerUuid.equals(p.getUUID()) && !p.hasPermissions(2))) {
                                                    MessageUtil.sendError(p, MODULE, "只有领地主人可以转让领地所有权。");
                                                    return 0;
                                                }
                                                c.ownerUuid = target.getUUID();
                                                c.ownerName = target.getGameProfile().getName();
                                                MessageUtil.sendSuccess(p, MODULE, "已将领地 [" + c.name + "] 正式转让给 " + c.ownerName + "！");
                                                MessageUtil.sendSuccess(target, MODULE, p.getName().getString() + " 将领地 [" + c.name + "] 转让给了您！");
                                                return 1;
                                            }))))
                    .then(Commands.literal("kick")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> {
                                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                if (c == null || (!c.canManage(p.getUUID()) && !p.hasPermissions(2))) {
                                                    MessageUtil.sendError(p, MODULE, "无权操作此领地。");
                                                    return 0;
                                                }
                                                String dim = target.serverLevel().dimension().location().toString();
                                                if (!c.contains(dim, target.blockPosition().getX(), target.blockPosition().getY(), target.blockPosition().getZ())) {
                                                    MessageUtil.sendError(p, MODULE, "目标玩家当前不在该领地内。");
                                                    return 0;
                                                }
                                                BlockPos spawn = target.server.overworld().getSharedSpawnPos();
                                                target.teleportTo(target.server.overworld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, java.util.Set.of(), 0f, 0f);
                                                MessageUtil.sendError(target, MODULE, "您已被领地管理员请出领地 [" + c.name + "]！");
                                                MessageUtil.sendSuccess(p, MODULE, "已将 " + target.getName().getString() + " 请出领地。");
                                                return 1;
                                            }))))
                    .then(Commands.literal("msg")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.literal("enter")
                                            .then(Commands.argument("text", StringArgumentType.greedyString())
                                                    .executes(ctx -> {
                                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                                        if (c == null || (!c.canManage(p.getUUID()) && !p.hasPermissions(2))) return 0;
                                                        c.enterMessage = StringArgumentType.getString(ctx, "text");
                                                        MessageUtil.sendSuccess(p, MODULE, "已更新领地进入提示语为: " + c.enterMessage);
                                                        return 1;
                                                    })))
                                    .then(Commands.literal("leave")
                                            .then(Commands.argument("text", StringArgumentType.greedyString())
                                                    .executes(ctx -> {
                                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                                        if (c == null || (!c.canManage(p.getUUID()) && !p.hasPermissions(2))) return 0;
                                                        c.leaveMessage = StringArgumentType.getString(ctx, "text");
                                                        MessageUtil.sendSuccess(p, MODULE, "已更新领地离开提示语为: " + c.leaveMessage);
                                                        return 1;
                                                    })))))
                    .then(Commands.literal("trust")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .then(Commands.argument("role", StringArgumentType.word())
                                                    .executes(ctx -> {
                                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                                        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                        String roleStr = StringArgumentType.getString(ctx, "role").toUpperCase(Locale.ROOT);
                                                        if (c == null || (!c.canManage(p.getUUID()) && !p.hasPermissions(2))) {
                                                            MessageUtil.sendError(p, MODULE, "领地不存在或您无管理权限。");
                                                            return 0;
                                                        }
                                                        ClaimRole role;
                                                        try {
                                                            role = ClaimRole.valueOf(roleStr);
                                                        } catch (IllegalArgumentException e) {
                                                            MessageUtil.sendError(p, MODULE, "角色无效！可用角色: ADMIN, TRUSTED, CONTAINER_TRUST, ACCESS_TRUST, BANNED");
                                                            return 0;
                                                        }
                                                        c.members.put(target.getUUID(), role);
                                                        c.memberNames.put(target.getUUID(), target.getGameProfile().getName());
                                                        MessageUtil.sendSuccess(p, MODULE, "已将玩家 " + target.getName().getString() + " 在领地 [" + c.name + "] 的权限设为 " + role.name());
                                                        return 1;
                                                    })))))
                    .then(Commands.literal("untrust")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> {
                                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                if (c == null || (!c.canManage(p.getUUID()) && !p.hasPermissions(2))) {
                                                    MessageUtil.sendError(p, MODULE, "领地不存在或您无管理权限。");
                                                    return 0;
                                                }
                                                c.members.remove(target.getUUID());
                                                MessageUtil.sendSuccess(p, MODULE, "已移除玩家 " + target.getName().getString() + " 在领地 [" + c.name + "] 的权限。");
                                                return 1;
                                            }))))
                    .then(Commands.literal("flag")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("flag", StringArgumentType.word())
                                            .then(Commands.argument("value", BoolArgumentType.bool())
                                                    .executes(ctx -> {
                                                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                        Claim c = ClaimManager.get().getClaimByName(StringArgumentType.getString(ctx, "name"));
                                                        if (c == null || (!c.canManage(p.getUUID()) && !p.hasPermissions(2))) {
                                                            MessageUtil.sendError(p, MODULE, "领地不存在或您无管理权限。");
                                                            return 0;
                                                        }
                                                        String flagName = StringArgumentType.getString(ctx, "flag").toUpperCase(Locale.ROOT);
                                                        ClaimFlag flag;
                                                        try {
                                                            flag = ClaimFlag.valueOf(flagName);
                                                        } catch (IllegalArgumentException e) {
                                                            MessageUtil.sendError(p, MODULE, "无效标志！输入 /claim gui " + c.name + " 可直接打开可视化开关面板！");
                                                            return 0;
                                                        }
                                                        boolean val = BoolArgumentType.getBool(ctx, "value");
                                                        c.flags.put(flag, val);
                                                        MessageUtil.sendSuccess(p, MODULE, "已将领地 [" + c.name + "] 的标志 " + flag.getTitle() + " (" + flag.name() + ") 设为 " + val);
                                                        return 1;
                                                    })))))
                    .then(Commands.literal("expand")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 256))
                                            .executes(ctx -> expandClaim(
                                                    ctx.getSource().getPlayerOrException(),
                                                    StringArgumentType.getString(ctx, "name"),
                                                    IntegerArgumentType.getInteger(ctx, "blocks")
                                            ))))));
        }
    }

    private static int sendClaimInvite(ServerPlayer sender, String claimName, ServerPlayer target, String roleStr) {
        Claim c = ClaimManager.get().getClaimByName(claimName);
        if (c == null || (!c.canManage(sender.getUUID()) && !sender.hasPermissions(2))) {
            MessageUtil.sendError(sender, MODULE, "未找到该领地或您没有管理权限。");
            return 0;
        }
        ClaimRole role;
        try {
            role = ClaimRole.valueOf(roleStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            role = ClaimRole.TRUSTED;
        }
        c.pendingInvites.put(target.getUUID(), role);
        MessageUtil.sendSuccess(sender, MODULE, "已向 " + target.getName().getString() + " 发送加入领地 [" + c.name + "] (" + role.name() + ") 的邀请函！");
        target.sendSystemMessage(MessageUtil.prefix("领地邀请", ChatFormatting.GOLD)
                .append(Component.literal(sender.getName().getString() + " 邀请您成为领地 [" + c.name + "] 的 " + role.name() + " 成员 ").withStyle(ChatFormatting.YELLOW))
                .append(MessageUtil.commandButton("接受邀请", "/claim accept " + c.name, "点击正式加入该领地", ChatFormatting.GREEN))
                .append(Component.literal(" "))
                .append(MessageUtil.commandButton("婉拒", "/claim deny " + c.name, "点击拒绝该邀请", ChatFormatting.RED)));
        MessageUtil.playDing(target);
        return 1;
    }

    private static int createClaim(ServerPlayer player, String name) {
        if (ClaimManager.get().getClaimByName(name) != null) {
            MessageUtil.sendError(player, MODULE, "领地名称 [" + name + "] 已被占用！");
            return 0;
        }
        List<Claim> myClaims = ClaimManager.get().getPlayerClaims(player.getUUID());
        int maxAllowed = ClaimManager.get().getMaxClaimsForPlayer(player.getUUID());
        if (myClaims.size() >= maxAllowed && !player.hasPermissions(2)) {
            MessageUtil.sendError(player, MODULE, "您已达到最大领地数量限制 (" + maxAllowed + ")。");
            return 0;
        }
        BlockPos p1 = ClaimManager.get().getPos1(player.getUUID());
        BlockPos p2 = ClaimManager.get().getPos2(player.getUUID());
        String dim = ClaimManager.get().getSelectionDim(player.getUUID());
        if (p1 == null || p2 == null || dim == null) {
            MessageUtil.sendError(player, MODULE, "请先使用【木锄头】左/右键或 /claim pos1 与 /claim pos2 选择两个对角点！");
            return 0;
        }
        if (!io.wifi.habitrainsurvive.world.WorldManager.get().assetAllowed(player, dim, false)) return 0;
        BlockPos fullP1 = new BlockPos(p1.getX(), Math.min(p1.getY(), -64), p1.getZ());
        BlockPos fullP2 = new BlockPos(p2.getX(), Math.max(p2.getY(), 320), p2.getZ());

        if (ClaimManager.get().hasIntersection(dim, fullP1, fullP2, null)) {
            MessageUtil.sendError(player, MODULE, "所选区域与现有领地重叠，无法创建！");
            return 0;
        }
        long area = (long) (Math.abs(p1.getX() - p2.getX()) + 1) * (long) (Math.abs(p1.getZ() - p2.getZ()) + 1);
        double cost = area * ClaimManager.get().getCostPerBlock2D();
        if (!EconomyManager.get().withdraw(player.getUUID(), cost, "Create Claim " + name)) {
            MessageUtil.sendError(player, MODULE, "余额不足！圈地面积 " + area + " 格，需要花费 " + EconomyManager.get().format(cost));
            return 0;
        }

        Claim claim = new Claim(
                name.toLowerCase(Locale.ROOT),
                name,
                player.getUUID(),
                player.getGameProfile().getName(),
                dim,
                fullP1,
                fullP2,
                GlobalPosInfo.fromPlayer(player)
        );
        ClaimManager.get().addClaim(claim);
        ClaimManager.get().startVisualization(player, claim, 15);
        player.sendSystemMessage(MessageUtil.prefix(MODULE, ChatFormatting.AQUA)
                .append(Component.literal("成功创建领地 [" + name + "] (面积: " + area + " 平方格, 花费: " + EconomyManager.get().format(cost) + ") ").withStyle(ChatFormatting.GREEN))
                .append(MessageUtil.commandButton("打开控制面板", "/claim gui " + name, "点击打开该领地的可视化控制中心", ChatFormatting.GOLD)));
        return 1;
    }

    private static int createSubClaim(ServerPlayer player, String parentName, String subName) {
        Claim parent = ClaimManager.get().getClaimByName(parentName);
        if (parent == null || (!parent.canManage(player.getUUID()) && !player.hasPermissions(2))) {
            MessageUtil.sendError(player, MODULE, "未找到父领地 [" + parentName + "] 或您没有管理权限！");
            return 0;
        }
        if (ClaimManager.get().getClaimByName(subName) != null) {
            MessageUtil.sendError(player, MODULE, "名称 [" + subName + "] 已存在！");
            return 0;
        }
        BlockPos p1 = ClaimManager.get().getPos1(player.getUUID());
        BlockPos p2 = ClaimManager.get().getPos2(player.getUUID());
        String dim = ClaimManager.get().getSelectionDim(player.getUUID());
        if (p1 == null || p2 == null || dim == null) {
            MessageUtil.sendError(player, MODULE, "请先用木锄头在父领地内部选择子领地的两个三维对角点！");
            return 0;
        }
        if (!io.wifi.habitrainsurvive.world.WorldManager.get().assetAllowed(player, dim, false)) return 0;
        if (!parent.containsFully(dim, p1, p2)) {
            MessageUtil.sendError(player, MODULE, "子领地选区必须完全包含在父领地 [" + parent.name + "] 内部！");
            return 0;
        }
        Claim sub = new Claim(
                subName.toLowerCase(Locale.ROOT),
                subName,
                player.getUUID(),
                player.getGameProfile().getName(),
                dim,
                p1,
                p2,
                GlobalPosInfo.fromPlayer(player)
        );
        sub.parentClaimId = parent.id;
        ClaimManager.get().addClaim(sub);
        ClaimManager.get().startVisualization(player, sub, 15);
        MessageUtil.sendSuccess(player, MODULE, "成功在 [" + parent.name + "] 内划分出独立权限子领地 [" + subName + "]！");
        return 1;
    }

    private static int deleteClaim(ServerPlayer player, String name) {
        Claim c = ClaimManager.get().getClaimByName(name);
        if (c == null) {
            MessageUtil.sendError(player, MODULE, "未找到领地 [" + name + "]。");
            return 0;
        }
        if (!c.ownerUuid.equals(player.getUUID()) && !player.hasPermissions(2)) {
            MessageUtil.sendError(player, MODULE, "只有领地所有者或管理员可以删除领地。");
            return 0;
        }
        ClaimManager.get().removeClaim(name);
        double refund = c.isSubClaim() ? 0.0 : c.getArea2D() * ClaimManager.get().getCostPerBlock2D() * 0.5;
        if (refund > 0) {
            EconomyManager.get().deposit(c.ownerUuid, refund, "Refund Claim " + c.name);
        }
        MessageUtil.sendSuccess(player, MODULE, "已删除领地 [" + c.name + "]，退还 50% 费用: " + EconomyManager.get().format(refund));
        return 1;
    }

    private static int expandClaim(ServerPlayer player, String name, int blocks) {
        Claim c = ClaimManager.get().getClaimByName(name);
        if (c == null || (!c.canManage(player.getUUID()) && !player.hasPermissions(2))) {
            MessageUtil.sendError(player, MODULE, "未找到领地或您没有管理权限。");
            return 0;
        }
        if (!io.wifi.habitrainsurvive.world.WorldManager.get().assetAllowed(player, c.dimension, false)) return 0;
        BlockPos newP1 = new BlockPos(c.minX - blocks, c.minY, c.minZ - blocks);
        BlockPos newP2 = new BlockPos(c.maxX + blocks, c.maxY, c.maxZ + blocks);
        if (ClaimManager.get().hasIntersection(c.dimension, newP1, newP2, c.id)) {
            MessageUtil.sendError(player, MODULE, "扩建后的边界会与其他领地冲突！");
            return 0;
        }
        long oldArea = c.getArea2D();
        long newArea = (long) (newP2.getX() - newP1.getX() + 1) * (long) (newP2.getZ() - newP1.getZ() + 1);
        double cost = (newArea - oldArea) * ClaimManager.get().getCostPerBlock2D();
        if (!EconomyManager.get().withdraw(player.getUUID(), cost, "Expand Claim " + c.name)) {
            MessageUtil.sendError(player, MODULE, "扩建需要额外费用 " + EconomyManager.get().format(cost) + "，您的余额不足！");
            return 0;
        }
        c.minX = newP1.getX();
        c.minZ = newP1.getZ();
        c.maxX = newP2.getX();
        c.maxZ = newP2.getZ();
        ClaimManager.get().startVisualization(player, c, 15);
        MessageUtil.sendSuccess(player, MODULE, "领地 [" + c.name + "] 已向四周扩展 " + blocks + " 格，花费 " + EconomyManager.get().format(cost));
        return 1;
    }

    private static int listClaims(ServerPlayer player) {
        List<Claim> list = ClaimManager.get().getPlayerClaims(player.getUUID());
        if (list.isEmpty()) {
            MessageUtil.sendInfo(player, MODULE, "您当前没有任何领地。输入 /claim wand 领取木锄头，左/右键选点后输入 /claim create <名称> 创建。");
            return 1;
        }
        player.sendSystemMessage(Component.literal("=== 您的领地列表 (" + list.size() + "/" + ClaimManager.get().getMaxClaimsForPlayer(player.getUUID()) + ") ===").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        for (Claim c : list) {
            player.sendSystemMessage(Component.literal("- [" + c.name + "] ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(String.format("范围: (%d,%d)~(%d,%d) 面积:%d ", c.minX, c.minZ, c.maxX, c.maxZ, c.getArea2D())).withStyle(ChatFormatting.WHITE))
                    .append(MessageUtil.commandButton("控制面板", "/claim gui " + c.name, "打开可视化控制面板", ChatFormatting.GOLD))
                    .append(Component.literal(" "))
                    .append(MessageUtil.commandButton("传送", "/claim tp " + c.name, "点击传送至领地", ChatFormatting.GREEN))
                    .append(Component.literal(" "))
                    .append(MessageUtil.commandButton("边界粒子", "/claim visualize " + c.name, "高亮显示领地边界", ChatFormatting.AQUA)));
        }
        return 1;
    }

    private static int showClaimInfo(ServerPlayer player, Claim c) {
        player.sendSystemMessage(Component.literal("=== 领地详情: [" + c.name + "] ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("领主: " + c.ownerName + " | 维度: " + c.dimension).withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.literal(String.format("边界: X[%d ~ %d], Y[%d ~ %d], Z[%d ~ %d] (面积: %d)",
                c.minX, c.maxX, c.minY, c.maxY, c.minZ, c.maxZ, c.getArea2D())).withStyle(ChatFormatting.WHITE));
        player.sendSystemMessage(MessageUtil.commandButton("打开此领地可视化控制面板", "/claim gui " + c.name, "点击打开控制面板", ChatFormatting.GREEN));
        return 1;
    }
}
