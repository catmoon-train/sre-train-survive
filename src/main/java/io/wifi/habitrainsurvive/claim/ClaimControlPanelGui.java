package io.wifi.habitrainsurvive.claim;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClaimControlPanelGui {
    private static ItemStack makeIcon(Item item, Component title, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, title);
        if (lore != null && !lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }

    public static void openMainSelector(ServerPlayer player) {
        SimpleContainer container = new SimpleContainer(54);
        ItemStack glass = makeIcon(Items.BLACK_STAINED_GLASS_PANE, Component.literal(" "), List.of());
        for (int i = 45; i < 54; i++) container.setItem(i, glass.copy());

        List<Claim> relevantClaims = ClaimManager.get().getAccessibleClaims(player.getUUID());
        for (int i = 0; i < Math.min(45, relevantClaims.size()); i++) {
            Claim c = relevantClaims.get(i);
            boolean isOwner = c.ownerUuid.equals(player.getUUID());
            ClaimRole myRole = c.getRole(player.getUUID());
            Item iconItem = c.isSubClaim() ? Items.LANTERN : (isOwner ? Items.BEACON : Items.CAMPFIRE);
            container.setItem(i, makeIcon(iconItem,
                    Component.literal((c.isSubClaim() ? "[子领地] " : "[领地] ") + c.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    List.of(
                            Component.literal("领主: " + c.ownerName + " | 我的身份: " + (myRole != null ? myRole.name() : "访客")).withStyle(ChatFormatting.YELLOW),
                            Component.literal(String.format("维度: %s | 面积: %d 平方格", c.dimension, c.getArea2D())).withStyle(ChatFormatting.AQUA),
                            Component.literal(String.format("范围: (%d, %d) ~ (%d, %d)", c.minX, c.minZ, c.maxX, c.maxZ)).withStyle(ChatFormatting.GRAY),
                            Component.literal("▶ 左键点击: 打开该领地控制面板").withStyle(ChatFormatting.GREEN),
                            Component.literal("▶ 右键点击: 立即传送至该领地").withStyle(ChatFormatting.LIGHT_PURPLE)
                    )));
        }

        // 底部快捷工具按钮
        container.setItem(47, makeIcon(Items.WOODEN_HOE,
                Component.literal("🪓 领取木锄圈地工具").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                List.of(
                        Component.literal("左键方块: 设置对角点 #1").withStyle(ChatFormatting.YELLOW),
                        Component.literal("右键方块: 设置对角点 #2").withStyle(ChatFormatting.YELLOW),
                        Component.literal("潜行+右键: 快速呼出当前领地控制面板").withStyle(ChatFormatting.AQUA)
                )));

        Claim standing = ClaimManager.get().getClaimAt(player.serverLevel().dimension().location().toString(), player.blockPosition());
        container.setItem(49, makeIcon(Items.COMPASS,
                Component.literal(standing != null ? "📍 管理当前脚下领地: [" + standing.name + "]" : "📍 当前位置: 荒野未圈地区域").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                List.of(Component.literal(standing != null ? "点击直接打开脚下领地的控制面板" : "使用木锄左/右键选点后输入 /claim create <名称> 创建").withStyle(ChatFormatting.GRAY))));

        container.setItem(51, makeIcon(Items.EMERALD,
                Component.literal("🏪 浏览全服挂牌出售中的领地").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                List.of(Component.literal("点击查看并购买其他玩家挂牌转让的领地").withStyle(ChatFormatting.YELLOW))));

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inv, container, 6) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId >= 0 && slotId < Math.min(45, relevantClaims.size())) {
                    Claim c = relevantClaims.get(slotId);
                    if (button == 1) {
                        sp.closeContainer();
                        sp.server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "claim tp " + c.name);
                    } else {
                        openClaimDashboard(sp, c);
                    }
                    return;
                }
                if (slotId == 47) {
                    sp.closeContainer();
                    ClaimManager.get().giveClaimWand(sp);
                } else if (slotId == 49 && standing != null) {
                    openClaimDashboard(sp, standing);
                } else if (slotId == 51) {
                    openForSaleClaimsMenu(sp);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("🏰 领地管理总控中心")));
    }

    public static void openClaimDashboard(ServerPlayer player, Claim claim) {
        if (io.wifi.habitrainsurvive.plot.PlotManager.get().isPlot(claim)) {
            io.wifi.habitrainsurvive.plot.PlotGui.openPlot(player, claim);
            return;
        }
        SimpleContainer container = new SimpleContainer(54);
        ItemStack bg = makeIcon(Items.GRAY_STAINED_GLASS_PANE, Component.literal(" "), List.of());
        for (int i = 0; i < 54; i++) container.setItem(i, bg.copy());

        boolean canManage = claim.canManage(player.getUUID()) || player.hasPermissions(2);

        // 顶部中央：领地核心概览卡 (Slot 4)
        container.setItem(4, makeIcon(Items.BEACON,
                Component.literal("🏰 领地 [" + claim.name + "] 总览").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                List.of(
                        Component.literal("领主: " + claim.ownerName).withStyle(ChatFormatting.YELLOW),
                        Component.literal("维度: " + claim.dimension + (claim.isSubClaim() ? " (父领地: " + claim.parentClaimId + ")" : "")).withStyle(ChatFormatting.AQUA),
                        Component.literal(String.format("坐标范围: X[%d~%d] Y[%d~%d] Z[%d~%d]", claim.minX, claim.maxX, claim.minY, claim.maxY, claim.minZ, claim.maxZ)).withStyle(ChatFormatting.WHITE),
                        Component.literal("平面面积: " + claim.getArea2D() + " 平方格 | 授权成员: " + claim.members.size() + " 人").withStyle(ChatFormatting.GREEN),
                        Component.literal(claim.forSale ? "💰 正在挂牌出售: " + EconomyManager.get().format(claim.salePrice) : "状态: 私人正常持有").withStyle(ChatFormatting.LIGHT_PURPLE)
                )));

        // 功能区第1排 (Slots 19, 21, 23, 25)
        container.setItem(19, makeIcon(Items.SHIELD,
                Component.literal("⚙ 领地安全防护与权限开关 (18项)").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                List.of(
                        Component.literal("可视化开关破坏、容器、PVP、防爆、活塞、").withStyle(ChatFormatting.GRAY),
                        Component.literal("流体涌入、火焰蔓延、农田保护、领地飞行等").withStyle(ChatFormatting.GRAY),
                        Component.literal(canManage ? "▶ 点击进入 18 项防护开关矩阵" : "▶ 点击查看当前领地防护配置").withStyle(ChatFormatting.GREEN)
                )));

        container.setItem(21, makeIcon(Items.PLAYER_HEAD,
                Component.literal("👥 领地成员管理与在线邀请 (" + claim.members.size() + "人)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                List.of(
                        Component.literal("查看所有信任成员、调整权限等级、移除成员").withStyle(ChatFormatting.GRAY),
                        Component.literal("或从在线玩家列表中一键发送领地邀请函").withStyle(ChatFormatting.GRAY),
                        Component.literal("▶ 点击进入成员与邀请面板").withStyle(ChatFormatting.GREEN)
                )));

        container.setItem(23, makeIcon(Items.END_ROD,
                Component.literal("✨ 开启 15 秒领地边界粒子墙预览").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                List.of(
                        Component.literal("在领地四周边框生成翠绿粒子光幕").withStyle(ChatFormatting.GRAY),
                        Component.literal("▶ 点击立即高亮显示领地边界").withStyle(ChatFormatting.GREEN)
                )));

        container.setItem(25, makeIcon(Items.ENDER_PEARL,
                Component.literal("🚀 立即传送至此领地").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                List.of(
                        Component.literal("传送目标点: " + (claim.teleportPos != null ? claim.teleportPos.toShortString() : "默认中心")).withStyle(ChatFormatting.GRAY),
                        Component.literal("▶ 点击启动读条传送").withStyle(ChatFormatting.AQUA)
                )));

        // 功能区第2排 (Slots 29, 31, 33, 40)
        container.setItem(29, makeIcon(Items.COMPASS,
                Component.literal("📍 将当前脚下位置设为领地传送点").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                List.of(Component.literal("仅限领地管理员在领地内部执行").withStyle(ChatFormatting.GRAY))));

        container.setItem(31, makeIcon(Items.GOLDEN_PICKAXE,
                Component.literal("📐 向四周快速扩建领地 (+5格 / +10格)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                List.of(
                        Component.literal("▶ 左键点击: 向东南西北各扩建 5 格").withStyle(ChatFormatting.GREEN),
                        Component.literal("▶ 右键点击: 向东南西北各扩建 10 格").withStyle(ChatFormatting.AQUA)
                )));

        container.setItem(33, makeIcon(Items.TNT,
                Component.literal("🗑 删除此领地 (退还 50% 圈地费)").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                List.of(
                        Component.literal("预计退还资金: " + EconomyManager.get().format(claim.getArea2D() * ClaimManager.get().getCostPerBlock2D() * 0.5)).withStyle(ChatFormatting.YELLOW),
                        Component.literal("▶ Shift+右键点击确认永久删除此领地").withStyle(ChatFormatting.RED)
                )));

        container.setItem(45, makeIcon(Items.ARROW,
                Component.literal("« 返回领地总控列表").withStyle(ChatFormatting.YELLOW),
                List.of()));

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inv, container, 6) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                switch (slotId) {
                    case 19 -> openFlagsMenu(sp, claim);
                    case 21 -> openMembersMenu(sp, claim);
                    case 23 -> {
                        ClaimManager.get().startVisualization(sp, claim, 15);
                        MessageUtil.sendSuccess(sp, "领地", "已开启领地 [" + claim.name + "] 粒子光幕边界显示 (15秒)！");
                        sp.closeContainer();
                    }
                    case 25 -> {
                        sp.closeContainer();
                        sp.server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "claim tp " + claim.name);
                    }
                    case 29 -> {
                        if (!canManage) {
                            MessageUtil.sendError(sp, "领地", "您没有管理该领地的权限！");
                            return;
                        }
                        claim.teleportPos = GlobalPosInfo.fromPlayer(sp);
                        MessageUtil.sendSuccess(sp, "领地", "已将领地 [" + claim.name + "] 的传送点更新为您的当前位置！");
                        openClaimDashboard(sp, claim);
                    }
                    case 31 -> {
                        int expandBlocks = (button == 1) ? 10 : 5;
                        sp.closeContainer();
                        sp.server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "claim expand " + claim.name + " " + expandBlocks);
                    }
                    case 33 -> {
                        if (clickType == ClickType.QUICK_MOVE && button == 1) {
                            sp.closeContainer();
                            sp.server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "claim delete " + claim.name);
                        } else {
                            MessageUtil.sendInfo(sp, "领地安全确认", "请按住 Shift + 右键点击该按钮以确认删除领地 [" + claim.name + "]！");
                        }
                    }
                    case 45 -> openMainSelector(sp);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("⚙ 领地控制面板: [" + claim.name + "]")));
    }

    public static void openFlagsMenu(ServerPlayer player, Claim claim) {
        SimpleContainer container = new SimpleContainer(54);
        ItemStack bg = makeIcon(Items.BLACK_STAINED_GLASS_PANE, Component.literal(" "), List.of());
        for (int i = 36; i < 54; i++) container.setItem(i, bg.copy());

        boolean canManage = claim.canManage(player.getUUID()) || player.hasPermissions(2);
        ClaimFlag[] allFlags = ClaimFlag.values();

        for (int i = 0; i < Math.min(36, allFlags.length); i++) {
            ClaimFlag flag = allFlags[i];
            boolean enabled = claim.getFlag(flag);
            container.setItem(i, makeIcon(flag.getIcon(),
                    Component.literal(flag.getTitle() + " (" + flag.name() + ")").withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD),
                    List.of(
                            Component.literal("说明: " + flag.getDescription()).withStyle(ChatFormatting.GRAY),
                            Component.literal("当前状态: " + (enabled ? "§a✔ 允许开启" : "§c✘ 严禁/开启保护")).withStyle(ChatFormatting.WHITE),
                            Component.literal(canManage ? "▶ 点击切换此项领地开关" : "您仅可查看此开关").withStyle(ChatFormatting.YELLOW)
                    )));
        }

        container.setItem(45, makeIcon(Items.ARROW, Component.literal("« 返回领地 [" + claim.name + "] 主面板").withStyle(ChatFormatting.YELLOW), List.of()));

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inv, container, 6) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId == 45) {
                    openClaimDashboard(sp, claim);
                    return;
                }
                if (slotId >= 0 && slotId < allFlags.length) {
                    if (!canManage) {
                        MessageUtil.sendError(sp, "领地", "只有领主或领地管理员可以修改防护标志！");
                        return;
                    }
                    ClaimFlag flag = allFlags[slotId];
                    boolean next = !claim.getFlag(flag);
                    claim.flags.put(flag, next);
                    MessageUtil.playDing(sp);
                    openFlagsMenu(sp, claim);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("🛡 领地防护设置: [" + claim.name + "]")));
    }

    public static void openMembersMenu(ServerPlayer player, Claim claim) {
        SimpleContainer container = new SimpleContainer(54);
        ItemStack bg = makeIcon(Items.BLACK_STAINED_GLASS_PANE, Component.literal(" "), List.of());
        for (int i = 45; i < 54; i++) container.setItem(i, bg.copy());

        boolean canManage = claim.canManage(player.getUUID()) || player.hasPermissions(2);
        List<Map.Entry<UUID, ClaimRole>> entries = new ArrayList<>(claim.members.entrySet());

        for (int i = 0; i < Math.min(45, entries.size()); i++) {
            Map.Entry<UUID, ClaimRole> entry = entries.get(i);
            UUID memberId = entry.getKey();
            ClaimRole role = entry.getValue();
            String mName = claim.memberNames.getOrDefault(memberId, memberId.toString().substring(0, 8));
            Item icon = role == ClaimRole.BANNED ? Items.BARRIER : Items.PLAYER_HEAD;
            container.setItem(i, makeIcon(icon,
                    Component.literal("成员: " + mName).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                    List.of(
                            Component.literal("当前角色: " + role.name()).withStyle(ChatFormatting.GOLD),
                            Component.literal(canManage ? "▶ 左键点击: 轮换升级/调整权限角色" : "").withStyle(ChatFormatting.GREEN),
                            Component.literal(canManage ? "▶ 右键点击: 将该玩家移出领地名单" : "").withStyle(ChatFormatting.RED)
                    )));
        }

        container.setItem(45, makeIcon(Items.ARROW, Component.literal("« 返回领地 [" + claim.name + "] 主面板").withStyle(ChatFormatting.YELLOW), List.of()));
        if (canManage) {
            container.setItem(49, makeIcon(Items.WRITABLE_BOOK,
                    Component.literal("➕ 点击从在线玩家列表发送领地邀请").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    List.of(Component.literal("向在线好友发送可点击接受的领地邀请函").withStyle(ChatFormatting.YELLOW))));
        }

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inv, container, 6) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId == 45) {
                    openClaimDashboard(sp, claim);
                    return;
                }
                if (slotId == 49 && canManage) {
                    openOnlineInviteMenu(sp, claim);
                    return;
                }
                if (slotId >= 0 && slotId < entries.size() && canManage) {
                    UUID targetId = entries.get(slotId).getKey();
                    ClaimRole current = entries.get(slotId).getValue();
                    if (button == 1) {
                        claim.members.remove(targetId);
                        MessageUtil.sendInfo(sp, "领地", "已移除该成员的领地权限。");
                    } else {
                        ClaimRole next = switch (current) {
                            case ACCESS_TRUST -> ClaimRole.CONTAINER_TRUST;
                            case CONTAINER_TRUST -> ClaimRole.TRUSTED;
                            case TRUSTED -> ClaimRole.ADMIN;
                            case ADMIN -> ClaimRole.BANNED;
                            default -> ClaimRole.ACCESS_TRUST;
                        };
                        claim.members.put(targetId, next);
                        MessageUtil.playDing(sp);
                    }
                    openMembersMenu(sp, claim);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("👥 领地成员管理: [" + claim.name + "]")));
    }

    public static void openOnlineInviteMenu(ServerPlayer player, Claim claim) {
        SimpleContainer container = new SimpleContainer(54);
        List<ServerPlayer> candidates = player.server.getPlayerList().getPlayers().stream()
                .filter(p -> !p.getUUID().equals(claim.ownerUuid) && !claim.members.containsKey(p.getUUID()))
                .toList();

        for (int i = 0; i < Math.min(45, candidates.size()); i++) {
            ServerPlayer target = candidates.get(i);
            container.setItem(i, makeIcon(Items.PLAYER_HEAD,
                    Component.literal("邀请玩家: " + target.getName().getString()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    List.of(
                            Component.literal("▶ 左键点击: 发送 [TRUSTED 信任建造者] 邀请").withStyle(ChatFormatting.AQUA),
                            Component.literal("▶ 右键点击: 发送 [ACCESS_TRUST 门禁访客] 邀请").withStyle(ChatFormatting.YELLOW)
                    )));
        }

        container.setItem(45, makeIcon(Items.ARROW, Component.literal("« 返回成员列表").withStyle(ChatFormatting.YELLOW), List.of()));

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inv, container, 6) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId == 45) {
                    openMembersMenu(sp, claim);
                    return;
                }
                if (slotId >= 0 && slotId < candidates.size()) {
                    ServerPlayer target = candidates.get(slotId);
                    String roleName = (button == 1) ? "ACCESS_TRUST" : "TRUSTED";
                    sp.closeContainer();
                    sp.server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(),
                            "claim invite " + claim.name + " " + target.getName().getString() + " " + roleName);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("📩 选择要邀请加入 [" + claim.name + "] 的玩家")));
    }

    public static void openForSaleClaimsMenu(ServerPlayer player) {
        SimpleContainer container = new SimpleContainer(54);
        List<Claim> forSaleList = ClaimManager.get().getAllClaims().stream().filter(c -> c.forSale).toList();
        for (int i = 0; i < Math.min(45, forSaleList.size()); i++) {
            Claim c = forSaleList.get(i);
            container.setItem(i, makeIcon(Items.GOLD_BLOCK,
                    Component.literal("待售领地: [" + c.name + "]").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    List.of(
                            Component.literal("原领主: " + c.ownerName + " | 面积: " + c.getArea2D() + " 格").withStyle(ChatFormatting.YELLOW),
                            Component.literal("转让一口价: " + EconomyManager.get().format(c.salePrice)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                            Component.literal("▶ 左键点击立即买下该领地所有权").withStyle(ChatFormatting.AQUA),
                            Component.literal("▶ 右键点击传送前往实地看房").withStyle(ChatFormatting.LIGHT_PURPLE)
                    )));
        }
        container.setItem(45, makeIcon(Items.ARROW, Component.literal("« 返回领地总控大厅").withStyle(ChatFormatting.YELLOW), List.of()));

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inv, container, 6) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId == 45) {
                    openMainSelector(sp);
                    return;
                }
                if (slotId >= 0 && slotId < forSaleList.size()) {
                    Claim c = forSaleList.get(slotId);
                    sp.closeContainer();
                    if (button == 1) {
                        TeleportWarmupManager.get().scheduleTeleportToPos(sp, "看房: " + c.name, c.teleportPos);
                    } else {
                        sp.server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "claim buy " + c.name);
                    }
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("🏪 全服在售领地交易市场")));
    }
}
