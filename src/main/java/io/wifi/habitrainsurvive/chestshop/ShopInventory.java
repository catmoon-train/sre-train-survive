package io.wifi.habitrainsurvive.chestshop;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Main inventory only: never trade equipped armour or the offhand, and never drop overflow. */
public final class ShopInventory {
    private ShopInventory() { }
    private static int size(Container c) { return c instanceof Inventory ? 36 : c.getContainerSize(); }
    private static int limit(Container c, ItemStack t) { return Math.min(c.getMaxStackSize(), t.getMaxStackSize()); }

    public static int count(Container c, ItemStack t) {
        if (c == null || t.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < size(c); i++) {
            ItemStack s = c.getItem(i);
            if (ItemStack.isSameItemSameComponents(s, t)) count += s.getCount();
        }
        return count;
    }

    public static int space(Container c, ItemStack t) {
        if (c == null || t.isEmpty()) return 0;
        int space = 0, max = limit(c, t);
        for (int i = 0; i < size(c); i++) {
            ItemStack s = c.getItem(i);
            if (!c.canPlaceItem(i, t)) continue;
            if (s.isEmpty()) space += max;
            else if (ItemStack.isSameItemSameComponents(s, t)) space += Math.max(0, max - s.getCount());
        }
        return space;
    }

    /** Call only after count/space checks, synchronously on the server thread. */
    public static void remove(Container c, ItemStack t, int amount) {
        if (amount <= 0 || count(c, t) < amount) throw new IllegalArgumentException("Insufficient stock");
        for (int i = 0; i < size(c) && amount > 0; i++) {
            ItemStack s = c.getItem(i);
            if (!ItemStack.isSameItemSameComponents(s, t)) continue;
            int n = Math.min(amount, s.getCount());
            c.removeItem(i, n);
            amount -= n;
        }
        c.setChanged();
    }

    public static void insert(Container c, ItemStack t, int amount) {
        if (amount <= 0 || space(c, t) < amount) throw new IllegalArgumentException("Insufficient space");
        int max = limit(c, t);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < size(c) && amount > 0; i++) {
                if (!c.canPlaceItem(i, t)) continue;
                ItemStack s = c.getItem(i);
                if (pass == 0 && !s.isEmpty() && ItemStack.isSameItemSameComponents(s, t)) {
                    int n = Math.min(amount, Math.max(0, max - s.getCount()));
                    s.grow(n);
                    amount -= n;
                } else if (pass == 1 && s.isEmpty()) {
                    int n = Math.min(amount, max);
                    c.setItem(i, t.copyWithCount(n));
                    amount -= n;
                }
            }
        }
        c.setChanged();
    }
}
