package io.wifi.habitrainsurvive.gift;

import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.cmi.KitData;
import io.wifi.habitrainsurvive.cmi.PlayerProfileData;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

public class GiftGui {
    public static void openGiftBox(ServerPlayer player) {
        List<GiftPackage> gifts = GiftManager.get().getGifts(player.getUUID());
        SimpleContainer container = new SimpleContainer(54);
        int limit = Math.min(45, gifts.size());

        for (int i = 0; i < limit; i++) {
            GiftPackage pkg = gifts.get(i);
            ItemStack icon = (pkg.serializedItem != null && !pkg.serializedItem.isBlank())
                    ? ItemSerializer.deserialize(pkg.serializedItem, player.server.registryAccess()).copy()
                    : new ItemStack(Items.SUNFLOWER);
            if (icon.isEmpty()) icon = new ItemStack(Items.CHEST);

            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("赠送者: " + pkg.senderName).withStyle(ChatFormatting.YELLOW));
            if (pkg.itemCount > 0) {
                lore.add(Component.literal("包含物品: " + pkg.itemDisplayName + " x" + pkg.itemCount).withStyle(ChatFormatting.AQUA));
            }
            if (pkg.attachedCoins > 0) {
                lore.add(Component.literal("附赠礼金: " + EconomyManager.get().format(pkg.attachedCoins)).withStyle(ChatFormatting.GOLD));
            }
            lore.add(Component.literal("祝福寄语: " + pkg.message).withStyle(ChatFormatting.LIGHT_PURPLE));
            lore.add(Component.literal("▶ 点击拆开并领取此礼物").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            icon.set(DataComponents.LORE, new ItemLore(lore));
            container.setItem(i, icon);
        }

        ItemStack glass = new ItemStack(Items.PINK_STAINED_GLASS_PANE);
        glass.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int s = 45; s < 54; s++) {
            container.setItem(s, glass.copy());
        }

        ItemStack kitCenterBtn = new ItemStack(Items.NETHER_STAR);
        kitCenterBtn.set(DataComponents.CUSTOM_NAME, Component.literal("前往服务器礼包领取中心 (/kits)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        container.setItem(49, kitCenterBtn);

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inv, container, 6) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId >= 0 && slotId < limit) {
                    GiftManager.get().claimGift(sp, slotId);
                    openGiftBox(sp);
                } else if (slotId == 49) {
                    openKitCenter(sp);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("🎁 我的私人礼盒 (" + gifts.size() + " 份待拆礼物)")));
    }

    public static void openKitCenter(ServerPlayer player) {
        List<KitData> kits = new ArrayList<>(CmiManager.get().getKits().values());
        SimpleContainer container = new SimpleContainer(27);
        PlayerProfileData prof = CmiManager.get().getOrCreateProfile(player);
        long now = System.currentTimeMillis();

        for (int i = 0; i < Math.min(18, kits.size()); i++) {
            KitData k = kits.get(i);
            ItemStack icon = new ItemStack(Items.ENDER_CHEST);
            if (!k.serializedItems.isEmpty()) {
                ItemStack first = ItemSerializer.deserialize(k.serializedItems.get(0), player.server.registryAccess());
                if (!first.isEmpty()) icon = first.copy();
            }
            icon.set(DataComponents.CUSTOM_NAME, Component.literal("礼包: [" + k.name + "]").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            long last = prof.kitCooldowns.getOrDefault(k.name.toLowerCase(), 0L);
            long remSec = Math.max(0L, (k.cooldownSeconds * 1000L - (now - last)) / 1000L);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("包含物品组数: " + k.serializedItems.size() + " 组").withStyle(ChatFormatting.AQUA));
            lore.add(Component.literal("领取费用: " + (k.cost > 0 ? EconomyManager.get().format(k.cost) : "免费")).withStyle(ChatFormatting.YELLOW));
            lore.add(remSec > 0
                    ? Component.literal("冷却剩余: " + remSec + " 秒").withStyle(ChatFormatting.RED)
                    : Component.literal("状态: ▶ 可立即点击领取！").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            icon.set(DataComponents.LORE, new ItemLore(lore));
            container.setItem(i, icon);
        }

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x3, syncId, inv, container, 3) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId >= 0 && slotId < kits.size()) {
                    KitData k = kits.get(slotId);
                    sp.server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "kit claim " + k.name);
                    openKitCenter(sp);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("🎉 服务器礼包领取中心")));
    }
}
