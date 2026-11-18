package io.wifi.habitrainsurvive.gift;

import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.cmi.KitData;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GiftManager {
    private static final GiftManager INSTANCE = new GiftManager();

    public static class Storage {
        public Map<UUID, List<GiftPackage>> playerGiftBoxes = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();

    public static GiftManager get() {
        return INSTANCE;
    }

    public void load(Path path, HolderLookup.Provider registries) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.playerGiftBoxes == null) {
            this.storage.playerGiftBoxes = new ConcurrentHashMap<>();
        }
        ensureDefaultKits(registries);
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    private void ensureDefaultKits(HolderLookup.Provider registries) {
        Map<String, KitData> kits = CmiManager.get().getKits();
        if (!kits.containsKey("newbie")) {
            List<String> newbieItems = List.of(
                    ItemSerializer.serialize(new ItemStack(Items.IRON_SWORD, 1), registries),
                    ItemSerializer.serialize(new ItemStack(Items.IRON_PICKAXE, 1), registries),
                    ItemSerializer.serialize(new ItemStack(Items.IRON_AXE, 1), registries),
                    ItemSerializer.serialize(new ItemStack(Items.COOKED_BEEF, 32), registries),
                    ItemSerializer.serialize(new ItemStack(Items.GOLDEN_HOE, 1), registries)
            );
            kits.put("newbie", new KitData("newbie", 86400 * 7, 0.0, newbieItems));
        }
        if (!kits.containsKey("daily_supply")) {
            List<String> dailyItems = List.of(
                    ItemSerializer.serialize(new ItemStack(Items.GOLDEN_APPLE, 2), registries),
                    ItemSerializer.serialize(new ItemStack(Items.COOKED_PORKCHOP, 16), registries),
                    ItemSerializer.serialize(new ItemStack(Items.TORCH, 64), registries)
            );
            kits.put("daily_supply", new KitData("daily_supply", 86400, 0.0, dailyItems));
        }
        if (!kits.containsKey("vip_pack")) {
            List<String> vipItems = List.of(
                    ItemSerializer.serialize(new ItemStack(Items.DIAMOND, 8), registries),
                    ItemSerializer.serialize(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1), registries),
                    ItemSerializer.serialize(new ItemStack(Items.EXPERIENCE_BOTTLE, 32), registries)
            );
            kits.put("vip_pack", new KitData("vip_pack", 86400, 50.0, vipItems));
        }
    }

    public List<GiftPackage> getGifts(UUID playerUuid) {
        return storage.playerGiftBoxes.computeIfAbsent(playerUuid, k -> new ArrayList<>());
    }

    public void sendGift(MinecraftServer server, UUID senderUuid, String senderName, UUID targetUuid,
                         ItemStack item, double coins, String message) {
        String serialized = (item != null && !item.isEmpty())
                ? ItemSerializer.serialize(item, server.registryAccess())
                : "";
        String itemName = (item != null && !item.isEmpty()) ? item.getHoverName().getString() : "红包礼金";
        int count = (item != null && !item.isEmpty()) ? item.getCount() : 0;

        GiftPackage pkg = new GiftPackage(
                UUID.randomUUID().toString().substring(0, 8),
                senderUuid,
                senderName,
                serialized,
                itemName,
                count,
                coins,
                message
        );
        getGifts(targetUuid).add(pkg);

        ServerPlayer onlineTarget = server.getPlayerList().getPlayer(targetUuid);
        if (onlineTarget != null) {
            MessageUtil.sendSuccess(onlineTarget, "礼物中心", "🎁 您收到了一份来自 [" + senderName + "] 的礼物 ("
                    + (count > 0 ? itemName + " x" + count + " " : "")
                    + (coins > 0 ? EconomyManager.get().format(coins) : "")
                    + ")！输入 /gift box 打开礼盒领取！");
            MessageUtil.playDing(onlineTarget);
        }
    }

    public synchronized boolean claimGift(ServerPlayer player, int index) {
        List<GiftPackage> list = getGifts(player.getUUID());
        if (index < 0 || index >= list.size()) return false;
        GiftPackage pkg = list.get(index);

        if (pkg.serializedItem != null && !pkg.serializedItem.isBlank()) {
            ItemStack stack = ItemSerializer.deserialize(pkg.serializedItem, player.server.registryAccess());
            if (!stack.isEmpty()) {
                if (!player.getInventory().add(stack)) {
                    MessageUtil.sendError(player, "礼物中心", "您的背包已满，请先腾出至少 1 格空位再拆开礼物！");
                    return false;
                }
            }
        }
        list.remove(index);
        if (pkg.attachedCoins > 0) {
            EconomyManager.get().deposit(player.getUUID(), pkg.attachedCoins, "Gift from " + pkg.senderName);
        }
        MessageUtil.sendSuccess(player, "礼物中心", "🎉 成功拆开来自 [" + pkg.senderName + "] 的礼物！寄语: " + pkg.message);
        MessageUtil.playDing(player);
        return true;
    }
}
