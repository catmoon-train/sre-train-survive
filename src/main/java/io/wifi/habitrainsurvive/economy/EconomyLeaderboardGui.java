package io.wifi.habitrainsurvive.economy;

import io.wifi.habitrainsurvive.menu.MenuManager;
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

/** Visual wealth ranking: 45 entries per page, no item transfer, own rank pinned in the footer. */
public final class EconomyLeaderboardGui {
    private static final int PAGE_SIZE = 45;
    private EconomyLeaderboardGui() {}

    private static ItemStack icon(Item item, String name, String... lines) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(lines)
                .map(line -> (Component) Component.literal("§7" + line)).toList()));
        return stack;
    }

    public static void open(ServerPlayer player, int requestedPage) {
        List<EconomyAccount> all = EconomyManager.get().getTopAccounts(Integer.MAX_VALUE);
        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) PAGE_SIZE));
        int page = Math.max(1, Math.min(requestedPage, totalPages));
        SimpleContainer container = new SimpleContainer(54);

        int start = (page - 1) * PAGE_SIZE;
        for (int i = start; i < Math.min(all.size(), start + PAGE_SIZE); i++) {
            EconomyAccount account = all.get(i);
            int rank = i + 1;
            boolean online = account.uuid != null && player.server.getPlayerList().getPlayer(account.uuid) != null;
            String name = (account.name == null || account.name.isBlank()) ? "未知账户" : account.name;
            String color = rank == 1 ? "§6§l" : rank <= 3 ? "§e" : "§f";
            ItemStack stack = icon(Items.PLAYER_HEAD, color + "#" + rank + " " + name);
            stack.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("余额: " + EconomyManager.get().format(account.balance)).withStyle(ChatFormatting.GREEN),
                    Component.literal("名次: #" + rank + " / " + all.size()).withStyle(ChatFormatting.AQUA),
                    Component.literal("状态: " + (online ? "在线" : "离线")).withStyle(online ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                    Component.literal("账户: " + (account.uuid == null ? "-" : account.uuid)).withStyle(ChatFormatting.DARK_GRAY))));
            container.setItem(i - start, stack);
        }

        long wealth = 0;
        for (EconomyAccount account : all) wealth += Math.max(0, account.balance);
        int ownRank = 0;
        for (int i = 0; i < all.size(); i++) {
            if (player.getUUID().equals(all.get(i).uuid)) { ownRank = i + 1; break; }
        }
        container.setItem(45, icon(Items.ARROW, "§e返回主菜单"));
        if (page > 1) container.setItem(46, icon(Items.PAPER, "§e上一页"));
        container.setItem(49, icon(Items.GOLD_INGOT, "§6我的排名",
                ownRank == 0 ? "尚未上榜（余额为 0 或账户未建立）" : "第 " + ownRank + " 名",
                "余额: " + EconomyManager.get().format(EconomyManager.get().getBalance(player.getUUID())),
                "服务器总财富: " + EconomyManager.get().format(wealth)));
        if (page < totalPages) container.setItem(52, icon(Items.PAPER, "§e下一页"));
        container.setItem(53, icon(Items.CLOCK, "§e刷新榜单", "共 " + all.size() + " 个账户 · 第 " + page + "/" + totalPages + " 页"));

        player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                new ChestMenu(MenuType.GENERIC_9x6, id, inventory, container, 6) {
                    @Override
                    public void clicked(int slot, int mouseButton, ClickType type, Player clicker) {
                        if (!(clicker instanceof ServerPlayer sp) || slot < 0 || slot > 53) return;
                        if (slot == 45) { MenuManager.get().open(sp, "main"); return; }
                        if (slot == 46 && page > 1) { open(sp, page - 1); return; }
                        if (slot == 52 && page < totalPages) { open(sp, page + 1); return; }
                        if (slot == 53) { open(sp, page); return; }
                    }

                    @Override
                    public ItemStack quickMoveStack(Player p, int index) { return ItemStack.EMPTY; }
                }, Component.literal("💰 经济排行榜 · 第 " + page + "/" + totalPages + " 页")));
    }
}
