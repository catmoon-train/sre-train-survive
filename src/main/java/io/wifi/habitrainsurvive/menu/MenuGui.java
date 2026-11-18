package io.wifi.habitrainsurvive.menu;

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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Server-only chest UI. No vanilla inventory operation is ever delegated to ChestMenu.clicked. */
public final class MenuGui {
    private MenuGui() { }

    public record Button(ItemStack icon, Consumer<ServerPlayer> action) {
        public Button {
            icon = Objects.requireNonNull(icon, "icon").copy();
            Objects.requireNonNull(action, "action");
        }
    }

    public static ItemStack icon(Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item == Items.AIR ? Items.PAPER : item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lore)
                .map(line -> (Component) Component.literal(line).withStyle(ChatFormatting.GRAY)
                        .withStyle(style -> style.withItalic(false))).toList()));
        return stack;
    }

    /** Five rows of content, followed by previous/back/page/close/next controls. Page indices start at zero. */
    public static void open(ServerPlayer player, String title, List<Button> buttons, int page,
                            Consumer<ServerPlayer> back) {
        List<Button> snapshot = List.copyOf(buttons);
        int pages = Math.max(1, (snapshot.size() + 44) / 45);
        int selected = Math.max(0, Math.min(page, pages - 1));
        Map<Integer, Button> slots = new HashMap<>();
        int start = selected * 45;
        for (int i = start; i < Math.min(start + 45, snapshot.size()); i++) slots.put(i - start, snapshot.get(i));
        for (int i = 45; i < 54; i++) slots.put(i, new Button(icon(Items.GRAY_STAINED_GLASS_PANE, " "), p -> { }));
        if (snapshot.isEmpty()) slots.put(22, new Button(icon(Items.PAPER, "暂无可显示的内容"), p -> { }));
        if (selected > 0) slots.put(45, new Button(icon(Items.ARROW, "上一页"), p -> open(p, title, snapshot, selected - 1, back)));
        if (back != null) slots.put(48, new Button(icon(Items.OAK_DOOR, "返回"), back));
        slots.put(49, new Button(icon(Items.PAPER, "第 " + (selected + 1) + " / " + pages + " 页",
                "共 " + snapshot.size() + " 项"), p -> { }));
        slots.put(50, new Button(icon(Items.BARRIER, "关闭"), ServerPlayer::closeContainer));
        if (selected + 1 < pages) slots.put(53, new Button(icon(Items.ARROW, "下一页"), p -> open(p, title, snapshot, selected + 1, back)));
        openSlots(player, title, 6, slots);
    }

    public static void openSlots(ServerPlayer player, String title, int rows, Map<Integer, Button> buttons) {
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("菜单行数必须为 1..6");
        Map<Integer, Button> snapshot = Map.copyOf(buttons);
        int size = rows * 9;
        SimpleContainer display = new SimpleContainer(size) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
        };
        snapshot.forEach((slot, button) -> {
            if (slot < 0 || slot >= size) throw new IllegalArgumentException("菜单槽位越界: " + slot);
            display.setItem(slot, button.icon().copy());
        });
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                new FrozenMenu(menuType(rows), id, inventory, display, rows, snapshot), Component.literal(title)));
    }

    /** Run after vanilla has applied its client-predicted slot cache, not from clicked(). */
    public static void resync(ServerPlayer player) {
        if (player.containerMenu instanceof FrozenMenu) player.containerMenu.sendAllDataToRemote();
        else GuiInput.resync(player);
    }

    private static MenuType<?> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("无效菜单行数");
        };
    }

    private static final class FrozenMenu extends ChestMenu {
        private final int displaySize;
        private final Map<Integer, Button> buttons;
        private int lastActionTick = Integer.MIN_VALUE;
        private boolean removed;

        private FrozenMenu(MenuType<?> type, int id, net.minecraft.world.entity.player.Inventory inventory,
                           SimpleContainer display, int rows, Map<Integer, Button> buttons) {
            super(type, id, inventory, display, rows);
            this.displaySize = rows * 9;
            this.buttons = buttons;
        }

        @Override
        public void clicked(int slot, int mouseButton, ClickType type, Player player) {
            if (!(player instanceof ServerPlayer serverPlayer) || removed || serverPlayer.containerMenu != this) return;
            // Including player inventory and outside clicks: freeze everything, and undo client prediction.
            if (!MenuInputPolicy.allows(type.name(), mouseButton, slot, displaySize)) {
                sendAllDataToRemote();
                return;
            }
            Button button = buttons.get(slot);
            if (button != null && lastActionTick != serverPlayer.tickCount) {
                lastActionTick = serverPlayer.tickCount;
                button.action().accept(serverPlayer);
            }
            if (serverPlayer.containerMenu == this) sendAllDataToRemote();
        }

        @Override
        public boolean stillValid(Player player) { return true; }

        @Override
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

        @Override
        public boolean canDragTo(Slot slot) { return false; }

        @Override
        public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return false; }

        @Override
        public void removed(Player player) {
            removed = true;
            // ChestMenu only returns the real carried stack and calls stopOpen; NEVER clearContainer(display).
            super.removed(player);
            getContainer().clearContent();
        }
    }
}
