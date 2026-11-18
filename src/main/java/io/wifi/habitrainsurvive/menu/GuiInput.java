package io.wifi.habitrainsurvive.menu;

import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.math.BigDecimal;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Server-only text input. All input and result stacks are display copies, never transferable. */
public final class GuiInput {
    private GuiInput() { }

    public static void open(ServerPlayer player, String title, String initial, BooleanSupplier valid,
                            Consumer<String> accepted, Runnable back) {
        if (!valid.getAsBoolean()) { MessageUtil.sendError(player, "编辑", "权限或目标已失效，请重新打开页面。"); return; }
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                new TextMenu(id, inventory, player, initial, valid, accepted, back), Component.literal(title)));
        if (player.containerMenu instanceof TextMenu menu) {
            menu.getSlot(0).set(MenuGui.icon(Items.PAPER, initial, "编辑上方文字；点击中间绿色按钮确认", "点击此纸张取消"));
            // The vanilla client can always click input slot 1, even with zero XP. A zero-cost result cannot be taken.
            menu.getSlot(1).set(MenuGui.icon(Items.LIME_DYE, "确认输入", "点击中间绿色按钮保存", "无需经验，不消耗真实物品"));
            menu.setItemName(initial);
            menu.sendAllDataToRemote();
        }
    }

    /** Called after vanilla applies its predicted slot cache, just like frozen chest menus. */
    public static void resync(ServerPlayer player) {
        if (player.containerMenu instanceof TextMenu) player.containerMenu.sendAllDataToRemote();
    }

    private static final class TextMenu extends AnvilMenu {
        private String value;
        private final BooleanSupplier valid;
        private final Consumer<String> accepted;
        private final Runnable back;
        private boolean removed;
        private int lastClick = Integer.MIN_VALUE;

        private TextMenu(int id, net.minecraft.world.entity.player.Inventory inventory, ServerPlayer player,
                         String initial, BooleanSupplier valid, Consumer<String> accepted, Runnable back) {
            super(id, inventory, ContainerLevelAccess.create(player.level(), player.blockPosition()));
            this.value = initial; this.valid = valid; this.accepted = accepted; this.back = back;
        }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public boolean setItemName(String name) {
            if (removed) return false;
            value = name; createResult(); return true;
        }
        @Override public void createResult() {
            if (removed) return;
            getSlot(2).set(MenuGui.icon(Items.LIME_DYE, "确认输入", "请点击中间绿色按钮确认", "不消耗经验或真实物品"));
            setData(0, 0); broadcastChanges();
        }
        @Override protected boolean mayPickup(Player player, boolean hasItem) { return true; }
        @Override public void clicked(int slot, int mouse, ClickType type, Player clicker) {
            if (!(clicker instanceof ServerPlayer player) || removed || player.containerMenu != this) return;
            if (!valid.getAsBoolean()) {
                MessageUtil.sendError(player, "编辑", "权限或目标已失效，请重新打开页面。"); player.closeContainer(); return;
            }
            if (type == ClickType.PICKUP && mouse == 0 && lastClick != player.tickCount) {
                lastClick = player.tickCount;
                if (slot == 0) back.run();
                else if (slot == 1 || slot == 2) {
                    try { accepted.accept(value); }
                    catch (IllegalArgumentException e) { MessageUtil.sendError(player, "编辑", e.getMessage()); }
                }
            }
            if (player.containerMenu == this) sendAllDataToRemote();
        }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean canDragTo(Slot slot) { return false; }
        @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return false; }
        @Override public void removed(Player player) {
            removed = true;
            getSlot(0).set(ItemStack.EMPTY); getSlot(1).set(ItemStack.EMPTY); getSlot(2).set(ItemStack.EMPTY);
            super.removed(player);
        }
    }

    public static double number(String input, double min, double max) {
        final double result;
        try { result = Double.parseDouble(input.trim()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("请输入有效数字"); }
        if (!Double.isFinite(result) || result < min || result > max)
            throw new IllegalArgumentException("数值必须在 " + min + " 到 " + max + " 之间");
        return result;
    }

    public static int integer(String input, int min, int max) {
        final int result;
        try { result = new BigDecimal(input.trim()).intValueExact(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("请输入有效整数"); }
        if (result < min || result > max) throw new IllegalArgumentException("整数必须在 " + min + " 到 " + max + " 之间");
        return result;
    }

    public static String text(String input, int max) {
        if (input == null || input.isBlank() || input.length() > max || input.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请输入1到" + max + "个有效字符");
        return input.trim();
    }
}
