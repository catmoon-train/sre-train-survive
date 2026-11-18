package io.wifi.habitrainsurvive.grave;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GraveManager {
    private static final GraveManager INSTANCE = new GraveManager();

    public static class Storage {
        public double remoteRedeemCost = 150.0;
        public Map<UUID, Boolean> autoInsuranceEnabled = new ConcurrentHashMap<>();
        public Map<UUID, List<GraveEntry>> playerGraves = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();

    public static GraveManager get() {
        return INSTANCE;
    }

    public void load(Path path) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.autoInsuranceEnabled == null) this.storage.autoInsuranceEnabled = new ConcurrentHashMap<>();
        if (this.storage.playerGraves == null) this.storage.playerGraves = new ConcurrentHashMap<>();
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    public boolean isInsuranceEnabled(UUID uuid) {
        return storage.autoInsuranceEnabled.getOrDefault(uuid, true);
    }

    public boolean toggleInsurance(UUID uuid) {
        boolean next = !isInsuranceEnabled(uuid);
        storage.autoInsuranceEnabled.put(uuid, next);
        return next;
    }

    public List<GraveEntry> getGraves(UUID uuid) {
        return storage.playerGraves.computeIfAbsent(uuid, k -> new ArrayList<>());
    }

    public boolean captureInventoryOnDeath(ServerPlayer player, Inventory inventory) {
        if (!isInsuranceEnabled(player.getUUID())) {
            return false;
        }
        List<String> saved = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack st = inventory.getItem(i);
            if (!st.isEmpty()) {
                saved.add(ItemSerializer.serialize(st, player.server.registryAccess()));
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        if (saved.isEmpty()) {
            return false;
        }
        String graveId = UUID.randomUUID().toString().substring(0, 6);
        GraveEntry entry = new GraveEntry(graveId, GlobalPosInfo.fromPlayer(player), storage.remoteRedeemCost, saved);
        List<GraveEntry> list = getGraves(player.getUUID());
        list.add(0, entry);
        while (list.size() > 10) {
            list.remove(list.size() - 1);
        }
        player.sendSystemMessage(MessageUtil.prefix("死亡墓碑保险", ChatFormatting.GOLD)
                .append(Component.literal("您的 " + saved.size() + " 格遗物已自动存入死亡墓碑保险箱，免遭岩浆销毁或他人拾取！ ").withStyle(ChatFormatting.YELLOW))
                .append(MessageUtil.commandButton("打开墓碑保险箱", "/grave", "点击取回死亡遗物", ChatFormatting.GREEN)));
        return true;
    }

    public synchronized void claimGrave(ServerPlayer player, int index) {
        List<GraveEntry> list = getGraves(player.getUUID());
        if (index < 0 || index >= list.size()) return;
        GraveEntry grave = list.get(index);

        boolean nearDeathSpot = io.wifi.habitrainsurvive.world.WorldManager.get().current(grave.deathPos)
                && player.serverLevel().dimension().location().toString().equals(grave.deathPos.dimension)
                && player.position().distanceToSqr(grave.deathPos.x, grave.deathPos.y, grave.deathPos.z) <= 64.0;

        if (!nearDeathSpot && grave.redeemFee > 0 && !player.hasPermissions(2)) {
            if (!EconomyManager.get().withdraw(player.getUUID(), grave.redeemFee, "Remote Grave Redeem #" + grave.id)) {
                MessageUtil.sendError(player, "死亡墓碑", "远程跨图取回遗物需要支付手续费 " + EconomyManager.get().format(grave.redeemFee)
                        + "（或右键点击前往死亡地点 8 格内免费领取）！");
                return;
            }
        }

        list.remove(index);
        for (String json : grave.serializedItems) {
            ItemStack st = ItemSerializer.deserialize(json, player.server.registryAccess());
            if (!st.isEmpty()) {
                if (!player.getInventory().add(st)) {
                    player.drop(st, false);
                }
            }
        }
        MessageUtil.sendSuccess(player, "死亡墓碑", "✨ 已成功取回墓碑 #" + grave.id + " 中的全部 " + grave.serializedItems.size() + " 组遗物！");
        MessageUtil.playDing(player);
    }

    public void openGui(ServerPlayer player) {
        List<GraveEntry> list = getGraves(player.getUUID());
        SimpleContainer container = new SimpleContainer(27);
        ItemStack bg = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        bg.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 18; i < 27; i++) container.setItem(i, bg.copy());

        for (int i = 0; i < Math.min(18, list.size()); i++) {
            GraveEntry g = list.get(i);
            ItemStack icon = new ItemStack(Items.SKELETON_SKULL);
            icon.set(DataComponents.CUSTOM_NAME, Component.literal("🪦 死亡遗物包 #" + g.id + " (" + g.serializedItems.size() + "组物品)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("死亡地点: " + g.deathPos.toShortString()).withStyle(ChatFormatting.AQUA),
                    Component.literal("远程提取费: " + EconomyManager.get().format(g.redeemFee) + " (在死亡点8格内免费)").withStyle(ChatFormatting.YELLOW),
                    Component.literal("▶ 左键点击: 立即提取全部遗物到背包").withStyle(ChatFormatting.GREEN),
                    Component.literal("▶ 右键点击: 传送前往该死亡坐标").withStyle(ChatFormatting.LIGHT_PURPLE)
            )));
            container.setItem(i, icon);
        }

        boolean enabled = isInsuranceEnabled(player.getUUID());
        ItemStack toggleBtn = new ItemStack(enabled ? Items.TOTEM_OF_UNDYING : Items.BARRIER);
        toggleBtn.set(DataComponents.CUSTOM_NAME, Component.literal("自动遗物保护: " + (enabled ? "§a✔ 已开启" : "§c✘ 已关闭")).withStyle(ChatFormatting.BOLD));
        toggleBtn.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("开启后死亡不掉落满地，而是自动收入此保险箱").withStyle(ChatFormatting.GRAY))));
        container.setItem(22, toggleBtn);

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x3, syncId, inv, container, 3) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId == 22) {
                    boolean next = toggleInsurance(sp.getUUID());
                    MessageUtil.sendInfo(sp, "死亡墓碑", "自动死亡遗物保险已切换为: " + (next ? "开启" : "关闭"));
                    openGui(sp);
                    return;
                }
                if (slotId >= 0 && slotId < Math.min(18, list.size())) {
                    GraveEntry g = list.get(slotId);
                    if (button == 1) {
                        sp.closeContainer();
                        TeleportWarmupManager.get().scheduleTeleportToPos(sp, "死亡地点 #" + g.id, g.deathPos);
                    } else {
                        claimGrave(sp, slotId);
                        openGui(sp);
                    }
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("🪦 死亡墓碑与遗物找回中心")));
    }
}
