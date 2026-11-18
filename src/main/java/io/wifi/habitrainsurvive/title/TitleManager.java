package io.wifi.habitrainsurvive.title;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TitleManager {
    private static final TitleManager INSTANCE = new TitleManager();

    public static class TitleDef {
        public String id;
        public String display;
        public double price;
        public String description;

        public TitleDef() {
        }

        public TitleDef(String id, String display, double price, String description) {
            this.id = id;
            this.display = display;
            this.price = price;
            this.description = description;
        }
    }

    public static class Storage {
        public Map<String, TitleDef> titles = new LinkedHashMap<>();
        public Map<UUID, Set<String>> unlockedTitles = new ConcurrentHashMap<>();
        public Map<UUID, String> equippedTitle = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();

    public static TitleManager get() {
        return INSTANCE;
    }

    public void load(Path path) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.titles == null) this.storage.titles = new LinkedHashMap<>();
        if (this.storage.unlockedTitles == null) this.storage.unlockedTitles = new ConcurrentHashMap<>();
        if (this.storage.equippedTitle == null) this.storage.equippedTitle = new ConcurrentHashMap<>();
        ensureDefaults();
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    private void ensureDefaults() {
        storage.titles.putIfAbsent("pioneer", new TitleDef("pioneer", "§b[开拓先锋]", 500.0, "献给踏入服务器荒野的勇敢拓荒者"));
        storage.titles.putIfAbsent("builder", new TitleDef("builder", "§a[建筑巨匠]", 1500.0, "巧夺天工的领地与城镇缔造者"));
        storage.titles.putIfAbsent("warrior", new TitleDef("warrior", "§c[无双战神]", 2500.0, "身经百战的巅峰强者专属头衔"));
        storage.titles.putIfAbsent("lucky", new TitleDef("lucky", "§d[天选欧皇]", 5000.0, "气运加身、一发入魂的幸运之星"));
        storage.titles.putIfAbsent("tycoon", new TitleDef("tycoon", "§6§l[富甲天下]", 20000.0, "掌控全服经济命脉的顶级商业大亨"));
    }

    public Map<String, TitleDef> getTitles() {
        return storage.titles;
    }

    public Set<String> getUnlocked(UUID uuid) {
        return storage.unlockedTitles.computeIfAbsent(uuid, k -> new HashSet<>());
    }

    public void equipTitle(ServerPlayer player, String titleId) {
        if (titleId == null || titleId.isEmpty()) {
            storage.equippedTitle.remove(player.getUUID());
            player.setCustomName(null);
            MessageUtil.sendSuccess(player, "称号中心", "已卸下当前佩戴的头衔称号。");
            return;
        }
        TitleDef def = storage.titles.get(titleId.toLowerCase(Locale.ROOT));
        if (def == null) return;
        storage.equippedTitle.put(player.getUUID(), def.id);
        player.setCustomName(Component.literal(def.display + " §f" + player.getGameProfile().getName()));
        player.setCustomNameVisible(true);
        MessageUtil.sendSuccess(player, "称号中心", "已成功佩戴称号: " + def.display);
        MessageUtil.playDing(player);
    }

    public void openGui(ServerPlayer player) {
        List<TitleDef> all = new ArrayList<>(storage.titles.values());
        Set<String> unlocked = getUnlocked(player.getUUID());
        String current = storage.equippedTitle.getOrDefault(player.getUUID(), "");

        SimpleContainer container = new SimpleContainer(27);
        ItemStack bg = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        bg.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 18; i < 27; i++) container.setItem(i, bg.copy());

        for (int i = 0; i < Math.min(18, all.size()); i++) {
            TitleDef t = all.get(i);
            boolean has = unlocked.contains(t.id);
            boolean active = t.id.equalsIgnoreCase(current);
            ItemStack icon = new ItemStack(active ? Items.NETHER_STAR : (has ? Items.NAME_TAG : Items.BOOK));
            icon.set(DataComponents.CUSTOM_NAME, Component.literal("头衔: " + t.display).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("简介: " + t.description).withStyle(ChatFormatting.GRAY),
                    Component.literal("解锁价格: " + EconomyManager.get().format(t.price)).withStyle(ChatFormatting.YELLOW),
                    Component.literal(active ? "✨ 当前正在佩戴 (点击可卸下)" : (has ? "✔ 已拥有 (点击立即佩戴)" : "▶ 点击花费金币购买并佩戴")).withStyle(active ? ChatFormatting.AQUA : (has ? ChatFormatting.GREEN : ChatFormatting.GOLD), ChatFormatting.BOLD)
            )));
            container.setItem(i, icon);
        }

        player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChestMenu(MenuType.GENERIC_9x3, syncId, inv, container, 3) {
            @Override
            public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
                if (!(clicker instanceof ServerPlayer sp)) return;
                if (slotId >= 0 && slotId < Math.min(18, all.size())) {
                    TitleDef t = all.get(slotId);
                    Set<String> mySet = getUnlocked(sp.getUUID());
                    String eq = storage.equippedTitle.getOrDefault(sp.getUUID(), "");
                    if (t.id.equalsIgnoreCase(eq)) {
                        equipTitle(sp, "");
                    } else if (mySet.contains(t.id)) {
                        equipTitle(sp, t.id);
                    } else {
                        if (!EconomyManager.get().withdraw(sp.getUUID(), t.price, "Buy Title " + t.id)) {
                            MessageUtil.sendError(sp, "称号商城", "金币不足！解锁称号 " + t.display + " §c需要 " + EconomyManager.get().format(t.price));
                            return;
                        }
                        mySet.add(t.id);
                        equipTitle(sp, t.id);
                    }
                    openGui(sp);
                }
            }

            @Override
            public ItemStack quickMoveStack(Player playerIn, int index) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("👑 称号商城与个人衣橱")));
    }
}
