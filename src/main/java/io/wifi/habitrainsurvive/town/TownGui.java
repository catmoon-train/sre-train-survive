package io.wifi.habitrainsurvive.town;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.GuiInput;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class TownGui {
    private static final String MODULE = "城镇";

    private TownGui() {
    }

    private static MenuGui.Button button(Item icon, String title, Consumer<ServerPlayer> action, String... lore) {
        return new MenuGui.Button(MenuGui.icon(icon, title, lore), p -> {
            try {
                action.accept(p);
            } catch (IllegalArgumentException e) {
                MessageUtil.sendError(p, MODULE, e.getMessage());
            }
        });
    }

    public static void open(ServerPlayer player) {
        Town town = TownManager.get().getPlayerTown(player.getUUID());
        if (town == null) {
            openTownList(player);
        } else {
            openDashboard(player, town);
        }
    }

    public static void openDashboard(ServerPlayer player, Town town) {
        if (!town.isResident(player.getUUID())) {
            openTownList(player);
            return;
        }
        int maxChunks = TownManager.get().getMaxChunksFor(town);
        double upkeep = Math.max(1, town.claimedChunks.size()) * TownManager.get().getDailyUpkeepPerChunk();
        TownRank myRank = town.getRank(player.getUUID());

        Map<Integer, MenuGui.Button> slots = new HashMap<>();
        slots.put(4, button(Items.BELL, "城镇: " + town.name, p -> openDashboard(p, town),
                "镇长: " + town.mayorName,
                "我的职级: " + (myRank != null ? myRank.getDisplayName() : "居民"),
                "居民数: " + town.residents.size() + " | 区块: " + town.claimedChunks.size() + " / " + maxChunks,
                "金库: " + EconomyManager.get().format(town.bankBalance) + " (日维护: " + EconomyManager.get().format(upkeep) + ")",
                "公告: " + (town.boardMessage == null ? "" : town.boardMessage)));

        slots.put(10, button(Items.ENDER_PEARL, "传送回城镇出生点", p -> {
            p.closeContainer();
            if (town.spawnPos != null) {
                TeleportWarmupManager.get().scheduleTeleportToPos(p, "城镇 " + town.name, town.spawnPos);
            } else {
                MessageUtil.sendError(p, MODULE, "城镇尚未设置出生点。");
            }
        }, "点击传送回 [" + town.name + "] 出生点"));

        slots.put(12, button(Items.PLAYER_HEAD, "居民与职级管理", p -> openResidents(p, town),
                "当前居民: " + town.residents.size() + " 人",
                "查看居民列表、在线状态、升降职与踢出"));

        slots.put(14, button(Items.GRASS_BLOCK, "领土与地块管理", p -> openPlots(p, town),
                "已占领区块: " + town.claimedChunks.size() + " / " + maxChunks,
                "占领/放弃脚下区块、挂牌出售、购买地块"));

        slots.put(16, button(Items.GOLD_INGOT, "城镇金库与税收", p -> openBank(p, town),
                "金库余额: " + EconomyManager.get().format(town.bankBalance),
                "每日居民税: " + EconomyManager.get().format(town.dailyTax),
                "每日维护费: " + EconomyManager.get().format(upkeep)));

        slots.put(20, button(Items.COMPARATOR, "城镇规则与设置", p -> openSettings(p, town),
                "PVP: " + (town.pvpEnabled ? "开启" : "关闭") + " | 爆炸: " + (town.explosionEnabled ? "允许" : "保护"),
                "火焰蔓延: " + (town.fireSpreadEnabled ? "允许" : "保护") + " | 对外开放: " + (town.openToPublic ? "是" : "否"),
                "居民默认建造: " + (town.allowAllResidentBuild ? "允许" : "禁止")));

        slots.put(22, button(Items.SHIELD, "外交同盟与通缉", p -> openDiplomacy(p, town),
                "同盟城镇: " + town.allies.size() + " 个",
                "通缉黑名单: " + town.outlaws.size() + " 人"));

        slots.put(24, button(Items.FILLED_MAP, "全服城镇列表", TownGui::openTownList,
                "查看服务器全部 " + TownManager.get().getAllTowns().size() + " 个城镇"));

        if (town.mayorUuid.equals(player.getUUID())) {
            slots.put(31, button(Items.TNT, "解散城镇", p -> confirmDisband(p, town),
                    "§c危险操作：解散后退还金库余额", "点击进入二次确认"));
        } else {
            slots.put(31, button(Items.OAK_DOOR, "离开城镇", p -> confirmLeave(p, town),
                    "退出城镇 [" + town.name + "]", "您在城镇内拥有的地块将被收回"));
        }

        slots.put(40, button(Items.BARRIER, "关闭", ServerPlayer::closeContainer));
        MenuGui.openSlots(player, "城镇管理 · " + town.name, 5, slots);
    }

    private static void confirmLeave(ServerPlayer player, Town town) {
        MenuGui.openSlots(player, "确认离开城镇 [" + town.name + "]", 3, Map.of(
                11, button(Items.RED_CONCRETE, "确认离开", p -> {
                    if (town.mayorUuid.equals(p.getUUID())) {
                        MessageUtil.sendError(p, MODULE, "镇长不能直接离开，请先转让镇长或解散城镇。");
                        return;
                    }
                    town.removeResident(p.getUUID());
                    MessageUtil.sendSuccess(p, MODULE, "您已离开城镇 [" + town.name + "]。");
                    p.closeContainer();
                }, "您在城内的个人地块将归还城镇"),
                15, button(Items.LIME_CONCRETE, "取消", p -> openDashboard(p, town))));
    }

    private static void confirmDisband(ServerPlayer player, Town town) {
        MenuGui.openSlots(player, "确认解散城镇 [" + town.name + "]", 3, Map.of(
                11, button(Items.RED_CONCRETE, "确认解散（不可撤销）", p -> {
                    if (!town.mayorUuid.equals(p.getUUID())) {
                        MessageUtil.sendError(p, MODULE, "只有镇长可以解散城镇。");
                        return;
                    }
                    TownManager.get().removeTown(town.id);
                    if (town.bankBalance > 0) {
                        EconomyManager.get().deposit(p.getUUID(), town.bankBalance, "Disband Town Bank Return");
                    }
                    MessageUtil.sendSuccess(p, MODULE, "城镇 [" + town.name + "] 已解散，金库剩余资金已退回。");
                    p.closeContainer();
                }, "将退还金库: " + EconomyManager.get().format(town.bankBalance)),
                15, button(Items.LIME_CONCRETE, "取消", p -> openDashboard(p, town))));
    }

    public static void openResidents(ServerPlayer player, Town town) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        boolean isMayor = town.mayorUuid.equals(player.getUUID());
        boolean isStaff = town.isStaff(player.getUUID());

        List<Map.Entry<UUID, TownRank>> sorted = new ArrayList<>(town.residents.entrySet());
        sorted.sort(Comparator.<Map.Entry<UUID, TownRank>>comparingInt(e -> e.getValue().getLevel()).reversed()
                .thenComparing(e -> town.getResidentName(e.getKey())));

        for (Map.Entry<UUID, TownRank> entry : sorted) {
            UUID uuid = entry.getKey();
            TownRank rank = entry.getValue();
            String name = town.getResidentName(uuid);
            boolean online = player.server.getPlayerList().getPlayer(uuid) != null;
            buttons.add(button(online ? Items.PLAYER_HEAD : Items.SKELETON_SKULL,
                    name + " [" + rank.getDisplayName() + "]",
                    p -> {
                        if (uuid.equals(town.mayorUuid) || uuid.equals(p.getUUID()) || !isStaff) {
                            return;
                        }
                        openResidentActions(p, town, uuid, name, rank, isMayor);
                    },
                    "状态: " + (online ? "§a在线" : "§7离线"),
                    (isStaff && !uuid.equals(town.mayorUuid) && !uuid.equals(p.getUUID())) ? "点击管理该居民" : "仅查看"));
        }
        MenuGui.open(player, "城镇居民 · " + town.name, buttons, 0, p -> openDashboard(p, town));
    }

    private static void openResidentActions(ServerPlayer player, Town town, UUID targetUuid, String targetName, TownRank currentRank, boolean isMayor) {
        Map<Integer, MenuGui.Button> slots = new HashMap<>();
        if (isMayor) {
            slots.put(10, button(Items.DIAMOND, "设为副镇长 (CO_MAYOR)", p -> {
                town.residents.put(targetUuid, TownRank.CO_MAYOR);
                MessageUtil.sendSuccess(p, MODULE, "已将 " + targetName + " 任命为副镇长。");
                openResidents(p, town);
            }, "当前职级: " + currentRank.getDisplayName()));
            slots.put(11, button(Items.EMERALD, "设为城镇助理 (ASSISTANT)", p -> {
                town.residents.put(targetUuid, TownRank.ASSISTANT);
                MessageUtil.sendSuccess(p, MODULE, "已将 " + targetName + " 任命为城镇助理。");
                openResidents(p, town);
            }));
            slots.put(12, button(Items.IRON_INGOT, "降为普通居民 (RESIDENT)", p -> {
                town.residents.put(targetUuid, TownRank.RESIDENT);
                MessageUtil.sendSuccess(p, MODULE, "已将 " + targetName + " 设为普通居民。");
                openResidents(p, town);
            }));
            slots.put(14, button(Items.GOLDEN_HELMET, "转让镇长", p -> {
                town.transferMayor(targetUuid, targetName);
                MessageUtil.sendSuccess(p, MODULE, "已将镇长职位转让给 " + targetName + "！");
                openDashboard(p, town);
            }, "§e您将成为副镇长"));
        }
        slots.put(16, button(Items.BARRIER, "踢出城镇", p -> {
            if (!town.isStaff(p.getUUID()) || targetUuid.equals(town.mayorUuid)) return;
            town.removeResident(targetUuid);
            MessageUtil.sendSuccess(p, MODULE, "已将 " + targetName + " 移出城镇。");
            openResidents(p, town);
        }, "§c踢出并收回其名下地块"));
        slots.put(22, button(Items.ARROW, "返回", p -> openResidents(p, town)));
        MenuGui.openSlots(player, "管理居民 · " + targetName, 3, slots);
    }

    public static void openPlots(ServerPlayer player, Town town) {
        String dim = player.serverLevel().dimension().location().toString();
        String key = Town.chunkKeyAt(dim, player.blockPosition());
        TownPlot currentPlot = town.claimedChunks.get(key);
        Town ownerHere = TownManager.get().getTownAt(dim, player.blockPosition());
        int maxChunks = TownManager.get().getMaxChunksFor(town);
        boolean isStaff = town.isStaff(player.getUUID());

        List<MenuGui.Button> buttons = new ArrayList<>();
        if (ownerHere == null && isStaff) {
            double cost = TownManager.get().getChunkClaimCost();
            buttons.add(button(Items.LIME_CONCRETE, "占领脚下区块", p -> {
                p.closeContainer();
                p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "town claim");
            }, "费用: " + EconomyManager.get().format(cost) + " (从金库扣除)",
                    "当前配额: " + town.claimedChunks.size() + " / " + maxChunks));
        } else if (currentPlot != null) {
            String ownerLabel = currentPlot.ownerUuid != null ? town.getResidentName(currentPlot.ownerUuid) : "城镇公共";
            buttons.add(button(Items.MAP, "当前区块状态", p -> { },
                    "坐标: (" + (player.blockPosition().getX() >> 4) + ", " + (player.blockPosition().getZ() >> 4) + ")",
                    "所有者: " + ownerLabel,
                    "出售状态: " + (currentPlot.forSale ? "出售中 " + EconomyManager.get().format(currentPlot.salePrice) : "未出售")));
            if (currentPlot.forSale && !player.getUUID().equals(currentPlot.ownerUuid)) {
                buttons.add(button(Items.GOLD_INGOT, "购买当前区块地块", p -> {
                    p.closeContainer();
                    p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "town plot buy");
                }, "价格: " + EconomyManager.get().format(currentPlot.salePrice)));
            }
            if (isStaff) {
                if (!currentPlot.forSale) {
                    buttons.add(button(Items.OAK_SIGN, "挂牌出售当前地块", p -> {
                        BooleanSupplier valid = () -> town.isStaff(p.getUUID()) && town.claimedChunks.containsKey(key);
                        GuiInput.open(p, "输入地块售价", "500", valid, val -> {
                            double price = GuiInput.number(val, 0, 1e12);
                            currentPlot.forSale = true;
                            currentPlot.salePrice = price;
                            MessageUtil.sendSuccess(p, MODULE, "已将当前区块挂牌出售，价格: " + EconomyManager.get().format(price));
                            openPlots(p, town);
                        }, () -> openPlots(p, town));
                    }));
                } else {
                    buttons.add(button(Items.RED_DYE, "取消挂牌出售", p -> {
                        currentPlot.forSale = false;
                        currentPlot.salePrice = 0.0;
                        MessageUtil.sendSuccess(p, MODULE, "已取消当前区块的出售挂牌。");
                        openPlots(p, town);
                    }));
                }
                if (currentPlot.ownerUuid != null) {
                    buttons.add(button(Items.IRON_SHOVEL, "收回地块所有权", p -> {
                        currentPlot.clearOwner();
                        MessageUtil.sendSuccess(p, MODULE, "已收回该地块，现归城镇公共所有。");
                        openPlots(p, town);
                    }));
                }
                buttons.add(button(Items.RED_CONCRETE, "放弃脚下区块主权", p -> {
                    p.closeContainer();
                    p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "town unclaim");
                }));
            }
        }

        for (TownPlot plot : town.claimedChunks.values()) {
            String owner = plot.ownerUuid != null ? town.getResidentName(plot.ownerUuid) : "公共";
            buttons.add(button(plot.forSale ? Items.GOLD_BLOCK : (plot.ownerUuid != null ? Items.OAK_PLANKS : Items.GRASS_BLOCK),
                    plot.chunkKey, p -> { },
                    "所有者: " + owner,
                    plot.forSale ? "§e挂牌出售: " + EconomyManager.get().format(plot.salePrice) : "未出售"));
        }

        MenuGui.open(player, "领土与地块 · " + town.name, buttons, 0, p -> openDashboard(p, town));
    }

    public static void openBank(ServerPlayer player, Town town) {
        boolean canWithdraw = town.getRank(player.getUUID()) != null
                && town.getRank(player.getUUID()).getLevel() >= TownRank.CO_MAYOR.getLevel();
        double upkeep = Math.max(1, town.claimedChunks.size()) * TownManager.get().getDailyUpkeepPerChunk();
        Map<Integer, MenuGui.Button> slots = new HashMap<>();

        slots.put(4, button(Items.GOLD_BLOCK, "城镇金库", p -> { },
                "当前余额: " + EconomyManager.get().format(town.bankBalance),
                "每日维护费: " + EconomyManager.get().format(upkeep),
                "每日居民税: " + EconomyManager.get().format(town.dailyTax)));

        slots.put(10, depositButton(town, 100));
        slots.put(11, depositButton(town, 1000));
        slots.put(12, depositButton(town, 10000));
        slots.put(13, button(Items.EMERALD, "自定义捐献金额", p -> {
            BooleanSupplier valid = () -> town.isResident(p.getUUID());
            GuiInput.open(p, "输入捐献金额", "500", valid, val -> {
                double amt = GuiInput.number(val, 0.01, 1e12);
                if (!EconomyManager.get().withdraw(p.getUUID(), amt, "Town Deposit " + town.name)) {
                    MessageUtil.sendError(p, MODULE, "您的个人余额不足！");
                } else {
                    town.bankBalance += amt;
                    MessageUtil.sendSuccess(p, MODULE, "已捐献 " + EconomyManager.get().format(amt));
                }
                openBank(p, town);
            }, () -> openBank(p, town));
        }));

        if (canWithdraw) {
            slots.put(16, button(Items.CHEST, "从金库取款", p -> {
                BooleanSupplier valid = () -> town.getRank(p.getUUID()) != null
                        && town.getRank(p.getUUID()).getLevel() >= TownRank.CO_MAYOR.getLevel();
                GuiInput.open(p, "输入取款金额", "100", valid, val -> {
                    double amt = GuiInput.number(val, 0.01, 1e12);
                    if (town.bankBalance < amt) {
                        MessageUtil.sendError(p, MODULE, "城镇金库余额不足！");
                    } else {
                        town.bankBalance -= amt;
                        EconomyManager.get().deposit(p.getUUID(), amt, "Town Withdraw " + town.name);
                        MessageUtil.sendSuccess(p, MODULE, "已提取 " + EconomyManager.get().format(amt));
                    }
                    openBank(p, town);
                }, () -> openBank(p, town));
            }, "仅镇长与副镇长可操作"));
        }

        slots.put(22, button(Items.ARROW, "返回", p -> openDashboard(p, town)));
        MenuGui.openSlots(player, "城镇金库 · " + town.name, 3, slots);
    }

    private static MenuGui.Button depositButton(Town town, double amount) {
        return button(Items.GOLD_INGOT, "捐献 " + EconomyManager.get().format(amount), p -> {
            if (!EconomyManager.get().withdraw(p.getUUID(), amount, "Town Deposit " + town.name)) {
                MessageUtil.sendError(p, MODULE, "您的个人余额不足！");
            } else {
                town.bankBalance += amount;
                MessageUtil.sendSuccess(p, MODULE, "已捐献 " + EconomyManager.get().format(amount));
            }
            openBank(p, town);
        });
    }

    public static void openSettings(ServerPlayer player, Town town) {
        boolean isStaff = town.isStaff(player.getUUID());
        boolean isMayor = town.mayorUuid.equals(player.getUUID());
        List<MenuGui.Button> buttons = new ArrayList<>();

        buttons.add(toggleButton(player, town, isStaff, "对外开放加入与传送", town.openToPublic, () -> town.openToPublic = !town.openToPublic));
        buttons.add(toggleButton(player, town, isStaff, "城镇 PVP", town.pvpEnabled, () -> town.pvpEnabled = !town.pvpEnabled));
        buttons.add(toggleButton(player, town, isStaff, "爆炸破坏", town.explosionEnabled, () -> town.explosionEnabled = !town.explosionEnabled));
        buttons.add(toggleButton(player, town, isStaff, "火焰蔓延", town.fireSpreadEnabled, () -> town.fireSpreadEnabled = !town.fireSpreadEnabled));
        buttons.add(toggleButton(player, town, isStaff, "全体居民公共建造", town.allowAllResidentBuild, () -> town.allowAllResidentBuild = !town.allowAllResidentBuild));
        buttons.add(toggleButton(player, town, isStaff, "访客公共交互", town.publicInteract, () -> town.publicInteract = !town.publicInteract));
        buttons.add(toggleButton(player, town, isStaff, "同城/同盟友军伤害", town.friendlyFire, () -> town.friendlyFire = !town.friendlyFire));

        if (isStaff) {
            buttons.add(button(Items.OAK_SIGN, "修改城镇公告", p -> {
                BooleanSupplier valid = () -> town.isStaff(p.getUUID());
                GuiInput.open(p, "输入城镇公告", town.boardMessage == null ? "" : town.boardMessage, valid, val -> {
                    town.boardMessage = GuiInput.text(val, 120);
                    MessageUtil.sendSuccess(p, MODULE, "城镇公告已更新。");
                    openSettings(p, town);
                }, () -> openSettings(p, town));
            }, "当前: " + (town.boardMessage == null ? "" : town.boardMessage)));

            buttons.add(button(Items.COMPASS, "将当前位置设为城镇出生点", p -> {
                town.spawnPos = GlobalPosInfo.fromPlayer(p);
                MessageUtil.sendSuccess(p, MODULE, "已更新城镇出生点。");
                openSettings(p, town);
            }));
        }

        if (isMayor) {
            buttons.add(button(Items.GOLD_NUGGET, "设置每日居民税", p -> {
                BooleanSupplier valid = () -> town.mayorUuid.equals(p.getUUID());
                GuiInput.open(p, "输入每日居民税", String.valueOf(town.dailyTax), valid, val -> {
                    town.dailyTax = GuiInput.number(val, 0, 10000);
                    MessageUtil.sendSuccess(p, MODULE, "每日居民税已设为 " + EconomyManager.get().format(town.dailyTax));
                    openSettings(p, town);
                }, () -> openSettings(p, town));
            }, "当前: " + EconomyManager.get().format(town.dailyTax)));
        }

        MenuGui.open(player, "城镇设置 · " + town.name, buttons, 0, p -> openDashboard(p, town));
    }

    private static MenuGui.Button toggleButton(ServerPlayer viewer, Town town, boolean canEdit, String label, boolean state, Runnable toggle) {
        return button(state ? Items.LIME_DYE : Items.RED_DYE,
                label + ": " + (state ? "§a开启" : "§c关闭"),
                p -> {
                    if (!canEdit) {
                        MessageUtil.sendError(p, MODULE, "只有城镇管理人员可以修改设置。");
                        return;
                    }
                    toggle.run();
                    openSettings(p, town);
                },
                canEdit ? "点击切换状态" : "仅城镇管理人员可修改");
    }

    public static void openDiplomacy(ServerPlayer player, Town town) {
        boolean isStaff = town.isStaff(player.getUUID());
        List<MenuGui.Button> buttons = new ArrayList<>();

        if (isStaff) {
            buttons.add(button(Items.EMERALD, "添加同盟城镇", p -> {
                BooleanSupplier valid = () -> town.isStaff(p.getUUID());
                GuiInput.open(p, "输入目标城镇名", "", valid, val -> {
                    Town other = TownManager.get().getTownByName(val.trim());
                    if (other == null || other.id.equals(town.id)) {
                        MessageUtil.sendError(p, MODULE, "目标城镇不存在或不能与自己结盟！");
                    } else {
                        town.allies.add(other.id);
                        MessageUtil.sendSuccess(p, MODULE, "已将 [" + other.name + "] 设为同盟城镇。");
                    }
                    openDiplomacy(p, town);
                }, () -> openDiplomacy(p, town));
            }));
        }

        for (String allyId : new ArrayList<>(town.allies)) {
            Town ally = TownManager.get().getTownByName(allyId);
            String display = ally != null ? ally.name : allyId;
            buttons.add(button(Items.BLUE_BANNER, "同盟: " + display, p -> {
                if (!isStaff) return;
                town.allies.remove(allyId);
                MessageUtil.sendSuccess(p, MODULE, "已解除与 [" + display + "] 的同盟关系。");
                openDiplomacy(p, town);
            }, isStaff ? "点击解除同盟" : "同盟城镇"));
        }

        for (UUID outlawId : new ArrayList<>(town.outlaws)) {
            String outlawName = town.outlawNames != null
                    ? town.outlawNames.getOrDefault(outlawId, outlawId.toString().substring(0, 8))
                    : outlawId.toString().substring(0, 8);
            buttons.add(button(Items.RED_BANNER, "通缉: " + outlawName, p -> {
                if (!isStaff) return;
                town.outlaws.remove(outlawId);
                if (town.outlawNames != null) town.outlawNames.remove(outlawId);
                MessageUtil.sendSuccess(p, MODULE, "已解除对 " + outlawName + " 的通缉。");
                openDiplomacy(p, town);
            }, isStaff ? "点击解除通缉" : "城镇通缉黑名单"));
        }

        MenuGui.open(player, "外交与通缉 · " + town.name, buttons, 0, p -> openDashboard(p, town));
    }

    public static void openTownList(ServerPlayer player) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        Town myTown = TownManager.get().getPlayerTown(player.getUUID());

        if (myTown == null) {
            buttons.add(button(Items.EMERALD_BLOCK, "创建新城镇", p -> {
                GuiInput.open(p, "输入新城镇名称", "", () -> TownManager.get().getPlayerTown(p.getUUID()) == null, val -> {
                    String clean = val.trim().replaceAll("\\s+", "_");
                    if (clean.isEmpty()) {
                        MessageUtil.sendError(p, MODULE, "城镇名称不能为空！");
                        return;
                    }
                    p.closeContainer();
                    p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "town create " + clean);
                }, () -> openTownList(p));
            }, "建城费用: " + EconomyManager.get().format(TownManager.get().getTownCreateCost()),
                    "将在您当前脚下区块建立城镇"));
        } else {
            buttons.add(button(Items.BELL, "打开我的城镇面板 [" + myTown.name + "]",
                    p -> openDashboard(p, myTown), "快速进入本城镇管理界面"));
        }

        TownManager.get().getAllTowns().stream()
                .sorted(Comparator.comparing(t -> t.name, String.CASE_INSENSITIVE_ORDER))
                .forEach(town -> {
                    boolean canJoin = myTown == null && !town.isOutlaw(player.getUUID())
                            && (town.openToPublic || town.pendingInvites.contains(player.getUUID()));
                    boolean isAlly = myTown != null && town.allies != null && town.allies.contains(myTown.id);
                    boolean canSpawn = town.openToPublic || town.isResident(player.getUUID()) || isAlly;
                    buttons.add(button(town.openToPublic ? Items.OAK_DOOR : Items.IRON_DOOR,
                            town.name,
                            p -> {
                                if (canJoin) {
                                    p.closeContainer();
                                    p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "town join " + town.name);
                                } else if (canSpawn && town.spawnPos != null) {
                                    p.closeContainer();
                                    TeleportWarmupManager.get().scheduleTeleportToPos(p, "城镇 " + town.name, town.spawnPos);
                                } else {
                                    MessageUtil.sendError(p, MODULE, "该城镇未对外开放。");
                                }
                            },
                            "镇长: " + town.mayorName,
                            "居民: " + town.residents.size() + " | 区块: " + town.claimedChunks.size(),
                            "金库: " + EconomyManager.get().format(town.bankBalance),
                            "公告: " + (town.boardMessage == null ? "" : town.boardMessage),
                            canJoin ? "§a点击加入该城镇" : (canSpawn ? "§b点击传送前往该城镇" : "§7未开放（需邀请）")));
                });

        MenuGui.open(player, "全服城镇列表 (" + TownManager.get().getAllTowns().size() + ")", buttons, 0,
                myTown != null ? p -> openDashboard(p, myTown) : null);
    }
}
