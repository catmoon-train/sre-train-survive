package io.wifi.habitrainsurvive.redpacket;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.MenuManager;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

public final class RedPacketGui {
    private RedPacketGui() {}

    private static ItemStack icon(Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(lore)
                .map(line -> (Component) Component.literal("§7" + line)).toList()));
        return stack;
    }

    public static void open(ServerPlayer player) {
        long now = System.currentTimeMillis();
        List<RedPacket> packets = RedPacketManager.get().active(now);
        SimpleContainer container = new SimpleContainer(54);
        for (int i = 0; i < Math.min(45, packets.size()); i++) {
            RedPacket packet = packets.get(i);
            boolean mine = player.getUUID().equals(packet.senderId());
            boolean claimed = packet.claimedBy(player.getUUID());
            String status = claimed ? "§7你已经抢过这个红包" : "§a点击抢一个（每人一份，先到先得）";
            ItemStack stack = new ItemStack(Items.FIREWORK_STAR);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c红包 · " + packet.senderName));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("总额: " + EconomyManager.get().format(packet.totalCents / 100.0)
                    + " / " + packet.count + " 份").withStyle(ChatFormatting.GOLD));
            lore.add(Component.literal("剩余: " + packet.remainingCount + " 份 · 未抢 "
                    + EconomyManager.get().format(packet.remainingCents / 100.0)).withStyle(ChatFormatting.YELLOW));
            lore.add(Component.literal("剩余时间: " + Math.max(0, (packet.expiresAt - now) / 1000) + " 秒")
                    .withStyle(ChatFormatting.GRAY));
            lore.add(Component.literal("留言: " + packet.message).withStyle(ChatFormatting.LIGHT_PURPLE));
            lore.add(Component.literal("编号: " + packet.id).withStyle(ChatFormatting.DARK_GRAY));
            if (mine) lore.add(Component.literal("你发出的红包，可用 /redpacket cancel " + packet.id + " 撤回").withStyle(ChatFormatting.AQUA));
            lore.add(Component.literal(status));
            stack.set(DataComponents.LORE, new ItemLore(lore));
            container.setItem(i, stack);
        }

        container.setItem(45, icon(Items.ARROW, "§e返回主菜单"));
        container.setItem(49, icon(Items.PAPER, "§e发红包",
                "指令: /redpacket send <总额> <份数> [留言]",
                "总额的每一份至少 0.01 金币，最多 100 份",
                "5 分钟内未抢完的部分自动退回"));
        container.setItem(53, icon(Items.CLOCK, "§e刷新列表", "当前 " + packets.size() + " 个可抢红包"));

        player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                new ChestMenu(MenuType.GENERIC_9x6, id, inventory, container, 6) {
                    @Override
                    public void clicked(int slot, int mouseButton, ClickType type, Player clicker) {
                        if (!(clicker instanceof ServerPlayer sp) || slot < 0 || slot > 53) return;
                        if (slot == 45) { MenuManager.get().open(sp, "main"); return; }
                        if (slot == 53) { open(sp); return; }
                        if (slot >= 45) return;
                        if (slot >= packets.size()) return;
                        RedPacketManager.get().claim(sp, packets.get(slot).id);
                        open(sp);
                    }

                    @Override
                    public ItemStack quickMoveStack(Player p, int index) { return ItemStack.EMPTY; }
                }, Component.literal("🧧 拼手气红包大厅")));
        if (packets.isEmpty()) MessageUtil.sendInfo(player, "拼手气红包", "当前没有可抢的红包，用 /redpacket send 发一个吧。");
    }
}
