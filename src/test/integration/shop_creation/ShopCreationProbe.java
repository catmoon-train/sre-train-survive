package smoke;

import com.mojang.authlib.GameProfile;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.GuiInput;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.sysshop.SystemShopAdminGui;
import io.wifi.habitrainsurvive.sysshop.SystemShopCategory;
import io.wifi.habitrainsurvive.sysshop.SystemShopGui;
import io.wifi.habitrainsurvive.sysshop.SystemShopItem;
import io.wifi.habitrainsurvive.sysshop.SystemShopManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.UUID;

/** Loaded only by run.py's scratch Fabric mod; never included in the production jar. */
public final class ShopCreationProbe implements ModInitializer {
    private static final String CATEGORY = "§a回归测试 中文 分类";
    private static final String COMMAND_CATEGORY = "命令创建 中文 分类";
    private static final String OP_CATEGORY = "生存管理员新增分类";
    private static final String HELD_NAME = "回归主手砖块";
    private static final String INVENTORY_NAME = "回归背包紫水晶";
    private static final String COMMAND_NAME = "回归命令碎片";
    private static final List<String> assertions = new ArrayList<>();
    private static int failures;

    private static void check(boolean ok, String name) {
        String line = (ok ? "PASS " : "FAIL ") + name;
        assertions.add(line);
        if (!ok) failures++;
        HabiTrainSurvive.LOGGER.info("SHOP_CREATION_CHECK {} {}", assertions.size(), line);
    }

    private static void need(boolean ok, String name) {
        check(ok, name);
        if (!ok) throw new AssertionError(name);
    }

    private static final class Probe extends FakePlayer {
        private int next;
        Probe(ServerLevel level, String name) {
            super(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
            setGameMode(GameType.SURVIVAL);
            experienceLevel = 0;
            experienceProgress = 0;
            totalExperience = 0;
        }
        @Override public OptionalInt openMenu(MenuProvider provider) {
            closeContainer();
            containerMenu = provider.createMenu(++next, getInventory(), this);
            return OptionalInt.of(next);
        }
        @Override public void closeContainer() {
            if (containerMenu != inventoryMenu) containerMenu.removed(this);
            containerMenu = inventoryMenu;
        }
        int button(String prefix) {
            if (!(containerMenu instanceof ChestMenu menu)) return -1;
            for (int i = 0; i < menu.getContainer().getContainerSize(); i++) {
                if (menu.getContainer().getItem(i).getHoverName().getString().startsWith(prefix)) return i;
            }
            return -1;
        }
        boolean click(String prefix) {
            int slot = button(prefix);
            if (slot < 0) return false;
            tickCount++;
            containerMenu.clicked(slot, 0, ClickType.PICKUP, this);
            MenuGui.resync(this);
            return true;
        }
        boolean submit(String value) {
            if (!(containerMenu instanceof AnvilMenu input)) return false;
            input.setItemName(value);
            // Exact original repro's client model: NOT the server-only mayPickup override.
            AnvilMenu client = new AnvilMenu(999, getInventory(), ContainerLevelAccess.NULL);
            for (int i = 0; i < 3; i++) client.getSlot(i).set(input.getSlot(i).getItem().copy());
            client.setData(0, input.getCost());
            for (int i = 0; i < 3; i++) {
                if (input.getSlot(i).getItem().getHoverName().getString().contains("确认")
                        && !client.getSlot(i).getItem().isEmpty() && client.getSlot(i).mayPickup(this)) {
                    tickCount++;
                    input.clicked(i, 0, ClickType.PICKUP, this);
                    MenuGui.resync(this);
                    return true;
                }
            }
            return false;
        }
    }

    @Override public void onInitialize() {
        boolean[] started = {false}, ran = {false};
        ServerLifecycleEvents.SERVER_STARTED.register(server -> started[0] = true);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Core data also loads on SERVER_STARTED; wait until every listener has finished.
            if (!started[0] || ran[0]) return;
            ran[0] = true;
            int phase = Integer.parseInt(System.getProperty("shop.creation.phase"));
            try {
                if (phase == 1) create(server);
                else if (phase == 2) restart(server);
                else throw new IllegalArgumentException("Unknown phase " + phase);
            } catch (Throwable error) {
                check(false, "uncaught " + error);
                HabiTrainSurvive.LOGGER.error("SHOP_CREATION_ERROR", error);
            } finally {
                String result = (failures == 0 ? "PASS " : "FAIL ") + "phase=" + phase + " checks="
                        + assertions.size() + " failed=" + failures + "\n" + String.join("\n", assertions) + "\n";
                try {
                    Files.writeString(Path.of(System.getProperty("shop.creation.result")), result, StandardCharsets.UTF_8);
                } catch (Exception error) {
                    HabiTrainSurvive.LOGGER.error("SHOP_CREATION_RESULT_ERROR", error);
                }
                server.halt(false);
            }
        });
    }

    private static SystemShopCategory byName(String name) {
        return SystemShopManager.get().getCategories().values().stream()
                .filter(c -> name.equals(c.displayName)).findFirst().orElse(null);
    }

    private static ItemStack sample(Item type, String name, int count) {
        ItemStack stack = new ItemStack(type, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("完整组件回归样本"))));
        return stack;
    }

    private static ItemStack template(Probe player, SystemShopItem item) {
        return ItemSerializer.deserialize(item.serializedItem, player.server.registryAccess());
    }

    private static SystemShopItem product(Probe player, SystemShopCategory category, Item type, String name) {
        return category.items.stream().filter(i -> {
            ItemStack stack = template(player, i);
            return stack.is(type) && name.equals(stack.getHoverName().getString());
        }).findFirst().orElse(null);
    }

    private static List<ItemStack> inventory(Probe player) {
        List<ItemStack> copy = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) copy.add(player.getInventory().getItem(i).copy());
        return copy;
    }

    private static boolean sameInventory(Probe player, List<ItemStack> before) {
        for (int i = 0; i < before.size(); i++) {
            ItemStack now = player.getInventory().getItem(i), old = before.get(i);
            if (now.getCount() != old.getCount() || !ItemStack.isSameItemSameComponents(now, old)) return false;
        }
        return player.containerMenu.getCarried().isEmpty() && player.inventoryMenu.getCarried().isEmpty();
    }

    private static void command(MinecraftServer server, Probe player, String command) {
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static boolean rejected(Runnable action) {
        try { action.run(); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }

    private static void startHeld(Probe admin, SystemShopCategory category, ItemStack stack) {
        admin.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
        SystemShopAdminGui.openCategory(admin, category, 0);
        need(admin.click("添加主手物品") && admin.containerMenu instanceof AnvilMenu,
                "held-item add starts price input, not a zero-price draft");
    }

    private static void prices(Probe admin, String buy, String sell) {
        need(admin.submit(buy) && admin.containerMenu instanceof AnvilMenu, "buy input advances to sell input");
        need(admin.submit(sell), "sell input has vanilla-pickable confirmation");
    }

    private static void create(MinecraftServer server) throws Exception {
        var manager = SystemShopManager.get();
        var permissions = PermissionManager.get();
        Probe rank = new Probe(server.overworld(), "CreateRankAdmin");
        Probe scoped = new Probe(server.overworld(), "CreationScoped");
        Probe op = new Probe(server.overworld(), "CreationOp");
        Probe regular = new Probe(server.overworld(), "CreationRegular");
        need(permissions.setPlayerGroup(rank.getUUID(), "admin"), "admin group exists after core-data load");
        permissions.addPlayerPerm(scoped.getUUID(), "habi.shop.admin");
        server.getPlayerList().op(op.getGameProfile());
        check(!rank.hasPermissions(2) && permissions.hasPermission(rank, "habi.admin"), "rank admin is non-OP");
        check(!scoped.hasPermissions(2) && !permissions.hasPermission(scoped, "habi.admin"), "scoped admin has only shop permission");
        for (Probe admin : List.of(rank, scoped, op)) {
            check(SystemShopManager.authorized(admin) && SystemShopManager.authorized(admin.createCommandSourceStack()),
                    admin.getGameProfile().getName() + " shared player/source authorization");
            command(server, admin, "shop admin");
            check(admin.containerMenu instanceof ChestMenu, admin.getGameProfile().getName() + " /shop admin opens");
            admin.closeContainer();
        }
        permissions.addPlayerPerm(regular.getUUID(), "habi.admin");
        check(SystemShopManager.authorized(regular) && SystemShopManager.authorized(regular.createCommandSourceStack()),
                "explicit habi.admin without OP or admin group is authorized");
        permissions.removePlayerPerm(regular.getUUID(), "habi.admin");

        command(server, rank, "shop admin");
        need(rank.click("新建分类") && rank.submit(CATEGORY), "rank admin submits Chinese/color category through GUI");
        SystemShopCategory category = byName(CATEGORY);
        need(category != null, "Chinese GUI category exists");
        check(category.id.matches("category_[a-z0-9_-]+"),
                "category has safe generated ID: " + category.id);
        check(rank.button("添加主手物品") >= 0, "new category is opened immediately");
        command(server, scoped, "shop addcategory " + COMMAND_CATEGORY);
        check(byName(COMMAND_CATEGORY) != null, "scoped /shop addcategory accepts greedy Chinese display name");
        SystemShopCategory unique1 = manager.createCategory(scoped, "重复显示名");
        SystemShopCategory unique2 = manager.createCategory(scoped, "重复显示名");
        check(!unique1.id.equals(unique2.id) && manager.isCurrent(unique1) && manager.isCurrent(unique2),
                "same display name still generates unique persistent IDs");
        manager.addCategory(scoped, "creation_legacy");
        check(manager.getCategory("creation_legacy") != null, "legacy explicit-ID addCategory remains usable");

        nativeInput(op);
        SystemShopAdminGui.open(op);
        need(op.click("新建分类") && op.submit(OP_CATEGORY), "zero-XP survival OP submits category GUI");
        check(byName(OP_CATEGORY) != null, "zero-XP OP category exists");

        ItemStack held = sample(Items.BRICK, HELD_NAME, 8);
        startHeld(rank, category, held);
        check(category.items.isEmpty(), "no item is appended before prices are submitted");
        List<ItemStack> rankBefore = inventory(rank);
        prices(rank, "12", "3");
        SystemShopItem heldProduct = product(rank, category, Items.BRICK, HELD_NAME);
        need(heldProduct != null && heldProduct.buyPrice == 12 && heldProduct.sellPrice == 3,
                "held component product has buy=12 sell=3");
        check(ItemStack.isSameItemSameComponents(template(rank, heldProduct), held), "held product preserves name and lore");
        SystemShopAdminGui.openItem(rank, category, heldProduct, 0);
        check(rank.button("商品名称") >= 0, "public openItem still opens the accepted product");
        rank.closeContainer();
        check(sameInventory(rank, rankBefore), "held sample conserved; no GUI icons enter inventory");

        ItemStack selected = sample(Items.AMETHYST_SHARD, INVENTORY_NAME, 7);
        scoped.getInventory().setItem(10, selected.copy());
        List<ItemStack> scopedBefore = inventory(scoped);
        SystemShopAdminGui.openCategory(scoped, category, 0);
        need(scoped.click("从背包选择物品") && scoped.click("槽位 11:"), "inventory selector chooses named sample by prefix");
        need(scoped.containerMenu instanceof AnvilMenu, "inventory selection starts price input");
        check(((AnvilMenu) scoped.containerMenu).getSlot(0).getItem().getHoverName().getString().matches("10(?:\\.0+)?"),
                "new-product buy price defaults to 10");
        need(scoped.submit("10") && scoped.containerMenu instanceof AnvilMenu, "inventory buy advances to sell input");
        check(((AnvilMenu) scoped.containerMenu).getSlot(0).getItem().getHoverName().getString().matches("0(?:\\.0+)?"),
                "new-product sell price defaults to 0");
        need(scoped.submit("0"), "inventory sell has native-pickable confirmation");
        SystemShopItem inventoryProduct = product(scoped, category, Items.AMETHYST_SHARD, INVENTORY_NAME);
        need(inventoryProduct != null && inventoryProduct.buyPrice == 10 && inventoryProduct.sellPrice == 0,
                "inventory product has buy=10 sell=0 (one disabled side allowed)");
        check(ItemStack.isSameItemSameComponents(template(scoped, inventoryProduct), selected), "inventory product preserves components");
        check(rejected(() -> manager.quote(inventoryProduct, false, 1, System.currentTimeMillis())), "zero sell price disables selling");
        scoped.closeContainer();
        check(sameInventory(scoped, scopedBefore), "inventory sample conserved; no selector icons enter inventory");

        for (Probe admin : List.of(rank, scoped, op)) {
            admin.closeContainer();
            command(server, admin, "shop add");
            boolean selector = admin.containerMenu instanceof ChestMenu;
            if (admin.button(CATEGORY) >= 0) admin.click(CATEGORY);
            check(selector && admin.button("添加主手物品") >= 0 && admin.button("从背包选择物品") >= 0,
                    admin.getGameProfile().getName() + " no-argument /shop add reaches add selector");
        }
        scoped.setItemInHand(InteractionHand.MAIN_HAND, sample(Items.PRISMARINE_SHARD, COMMAND_NAME, 5));
        command(server, scoped, "shop add " + category.id + " 9 2");
        SystemShopItem commandProduct = product(scoped, category, Items.PRISMARINE_SHARD, COMMAND_NAME);
        need(commandProduct != null && commandProduct.buyPrice == 9 && commandProduct.sellPrice == 2,
                "legacy /shop add <id> <buy> <sell> still creates a priced item");
        check(scoped.getMainHandItem().getCount() == 5, "command add copies sample");

        invalidAndDuplicate(rank, category, held);
        publicEntries(server, rank, regular, category);
        denial(server, regular, category);
        revoke(scoped, category);
        purchase(regular, heldProduct, "newly-created");
        check(op.experienceLevel == 0 && op.totalExperience == 0 && !op.getAbilities().instabuild,
                "all OP GUI operations leave survival experience unchanged");
        check(rank.experienceLevel == 0 && scoped.experienceLevel == 0, "non-OP workflows consume no XP");
        for (Probe player : List.of(rank, scoped, op, regular)) player.closeContainer();

        Properties state = new Properties();
        state.setProperty("category", category.id);
        state.setProperty("held", heldProduct.id);
        state.setProperty("inventory", inventoryProduct.id);
        state.setProperty("command", commandProduct.id);
        state.setProperty("categoryCount", Integer.toString(manager.getCategories().size()));
        try (var writer = Files.newBufferedWriter(Path.of(System.getProperty("shop.creation.state")), StandardCharsets.UTF_8)) {
            state.store(writer, "Stable generated IDs for phase 2");
        }
        // No forced load/save here: ordinary creation mutations and normal server shutdown must persist.
    }

    private static void nativeInput(Probe op) {
        check(op.hasPermissions(2) && !op.getAbilities().instabuild && op.experienceLevel == 0,
                "native input fixture is survival OP at zero XP");
        String[] accepted = {""};
        List<ItemStack> before = inventory(op);
        GuiInput.open(op, "输入回归", "initial", () -> true, value -> accepted[0] = value, op::closeContainer);
        need(op.containerMenu instanceof AnvilMenu, "text input uses native anvil menu");
        AnvilMenu input = (AnvilMenu) op.containerMenu;
        check(input.getSlot(0).getItem().is(Items.PAPER) && input.getSlot(1).getItem().is(Items.LIME_DYE)
                && input.getSlot(1).getItem().getHoverName().getString().contains("确认"),
                "native contract: paper cancel slot0, lime confirmation slot1");
        int[] fullSyncs = {0};
        input.setSynchronizer((net.minecraft.world.inventory.ContainerSynchronizer) java.lang.reflect.Proxy.newProxyInstance(
                ShopCreationProbe.class.getClassLoader(), new Class<?>[]{net.minecraft.world.inventory.ContainerSynchronizer.class},
                (proxy, method, args) -> { if (method.getName().equals("sendInitialData")) fullSyncs[0]++; return null; }));
        fullSyncs[0] = 0;
        MenuGui.resync(op);
        check(fullSyncs[0] > 0, "post-packet MenuGui.resync forwards a full sync to text input");
        AnvilMenu client = new AnvilMenu(999, op.getInventory(), ContainerLevelAccess.NULL);
        for (int i = 0; i < 3; i++) client.getSlot(i).set(input.getSlot(i).getItem().copy());
        client.setData(0, input.getCost());
        check(client.getSlot(1).mayPickup(op), "vanilla client permits middle-slot confirmation at zero XP");
        check(!client.getSlot(2).mayPickup(op), "vanilla result slot at zero cost is not treated as native-pickable");
        for (ClickType type : List.of(ClickType.QUICK_MOVE, ClickType.SWAP, ClickType.THROW, ClickType.CLONE, ClickType.PICKUP_ALL)) {
            for (int slot = 0; slot < 3; slot++) {
                op.tickCount++;
                input.clicked(slot, 0, type, op);
                MenuGui.resync(op);
            }
        }
        check(accepted[0].isEmpty() && sameInventory(op, before), "non-primary input clicks transfer no icons and submit nothing");
        need(op.submit("原生确认成功") && accepted[0].equals("原生确认成功"), "client-model-selected confirmation submits text");
        check(input.getCarried().isEmpty() && sameInventory(op, before), "confirmation cannot take its icon");
        op.closeContainer();
        check(sameInventory(op, before), "closing input returns no fake paper or lime/result icons");
        check(input.getSlot(0).getItem().isEmpty() && input.getSlot(1).getItem().isEmpty() && input.getSlot(2).getItem().isEmpty(),
                "closed input clears all display slots");
        GuiInput.open(op, "取消回归", "cancel", () -> true, value -> accepted[0] = "BAD", op::closeContainer);
        op.tickCount++;
        op.containerMenu.clicked(0, 0, ClickType.PICKUP, op);
        check(op.containerMenu == op.inventoryMenu && !accepted[0].equals("BAD") && sameInventory(op, before),
                "paper slot cancels without submitting or restoring fake items");
    }

    private static void invalidAndDuplicate(Probe admin, SystemShopCategory category, ItemStack duplicate) {
        int before = category.items.size();
        ItemStack fresh = sample(Items.ECHO_SHARD, "不应部分保存", 4);
        startHeld(admin, category, fresh);
        for (String invalid : List.of("not-a-price", "NaN", "-1", "100000000000000000000")) {
            need(admin.submit(invalid), "invalid buy input still has clickable confirmation");
            check(category.items.size() == before && admin.containerMenu instanceof AnvilMenu,
                    "invalid buy rejected without partial append: " + invalid);
        }
        need(admin.submit("10") && admin.containerMenu instanceof AnvilMenu, "valid buy advances after invalid retries");
        need(admin.submit("NaN"), "invalid sell input submitted");
        check(category.items.size() == before, "invalid sell rejected without partial append");
        admin.closeContainer();
        startHeld(admin, category, fresh);
        prices(admin, "0", "0");
        check(category.items.size() == before, "GUI rejects both-zero prices without appending draft");
        admin.closeContainer();
        admin.setItemInHand(InteractionHand.MAIN_HAND, duplicate.copy());
        SystemShopAdminGui.openCategory(admin, category, 0);
        need(admin.click("添加主手物品"), "duplicate GUI add action is reachable");
        // Implementations may reject the duplicate either before prices or on final commit.
        if (admin.containerMenu instanceof AnvilMenu) {
            if (admin.submit("12") && admin.containerMenu instanceof AnvilMenu) admin.submit("3");
        }
        check(category.items.size() == before, "duplicate components never append a partial product");
        admin.closeContainer();
        check(rejected(() -> SystemShopManager.get().addItem(admin, category, duplicate, 12, 3))
                && category.items.size() == before, "direct duplicate add remains atomic");
    }

    private static void publicEntries(MinecraftServer server, Probe admin, Probe regular, SystemShopCategory category) {
        command(server, admin, "shop open " + category.id);
        check(admin.button("商城管理") >= 0 && admin.button("新增商品") >= 0, "category player view exposes admin entry and current-category add");
        need(admin.click("新增商品"), "current-category add entry is clickable");
        check(admin.button("添加主手物品") >= 0 && admin.button("从背包选择物品") >= 0,
                "current-category add opens selector directly, not a category list");
        command(server, admin, "shop open " + category.id);
        check(admin.click("商城管理") && admin.button("新建分类") >= 0, "direct management entry opens admin root");
        command(server, regular, "shop open " + category.id);
        check(regular.containerMenu instanceof ChestMenu && regular.button("商城管理") < 0 && regular.button("新增商品") < 0,
                "ordinary buyer can browse category but sees no admin actions");
        regular.closeContainer();
    }

    private static void denial(MinecraftServer server, Probe regular, SystemShopCategory category) {
        var manager = SystemShopManager.get();
        int categories = manager.getCategories().size(), items = category.items.size();
        check(!SystemShopManager.authorized(regular) && !SystemShopManager.authorized(regular.createCommandSourceStack()),
                "ordinary player fails both authorization overloads");
        regular.setItemInHand(InteractionHand.MAIN_HAND, sample(Items.NAUTILUS_SHELL, "拒绝样本", 2));
        for (String cmd : List.of("shop admin", "shop add", "shop addcategory 普通玩家不应创建", "shop add " + category.id + " 10 1")) {
            regular.closeContainer();
            command(server, regular, cmd);
            check(regular.containerMenu == regular.inventoryMenu && manager.getCategories().size() == categories
                    && category.items.size() == items, "ordinary command denied: /" + cmd);
        }
        check(rejected(() -> manager.createCategory(regular, "直接拒绝")), "ordinary direct createCategory denied");
        check(rejected(() -> manager.addCategory(regular, "denied_legacy")), "ordinary direct addCategory denied");
        check(rejected(() -> manager.addItem(regular, category, regular.getMainHandItem(), 10, 1)), "ordinary direct addItem denied");
        check(manager.getCategories().size() == categories && category.items.size() == items, "denied direct mutations change no data");
        SystemShopAdminGui.open(regular);
        check(regular.containerMenu == regular.inventoryMenu, "ordinary direct admin GUI denied");
        regular.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static void revoke(Probe scoped, SystemShopCategory category) {
        int before = category.items.size();
        startHeld(scoped, category, sample(Items.HEART_OF_THE_SEA, "撤权不应创建", 3));
        need(scoped.submit("11") && scoped.containerMenu instanceof AnvilMenu, "revocation fixture reaches final sell input");
        List<ItemStack> inventoryBefore = inventory(scoped);
        PermissionManager.get().removePlayerPerm(scoped.getUUID(), "habi.shop.admin");
        need(scoped.submit("1"), "revoked final submission uses native-pickable confirmation");
        check(!SystemShopManager.authorized(scoped) && category.items.size() == before, "permission rechecked at submit; no revoked partial item");
        scoped.closeContainer();
        check(sameInventory(scoped, inventoryBefore), "revoked/closed input conserves sample and returns no icons");
    }

    private static void purchase(Probe buyer, SystemShopItem item, String phase) {
        buyer.closeContainer();
        var manager = SystemShopManager.get();
        var economy = EconomyManager.get();
        economy.getOrCreateAccount(buyer);
        economy.setBalance(buyer.getUUID(), 1000, "shop creation fixture");
        int before = manager.countSellable(buyer, item);
        double quoted = manager.quote(item, true, 1, System.currentTimeMillis()).total();
        manager.buyItem(buyer, item, 1);
        double debit = 1000 - economy.getBalance(buyer.getUUID());
        check(!SystemShopManager.authorized(buyer) && manager.countSellable(buyer, item) == before + 1
                && quoted > 0 && Math.abs(debit - quoted) < 0.011,
                phase + " product actually purchased by regular buyer via manager.buyItem; quoted=" + quoted + " debit=" + debit);
    }

    private static void restart(MinecraftServer server) throws Exception {
        Properties state = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(System.getProperty("shop.creation.state")), StandardCharsets.UTF_8)) {
            state.load(reader);
        }
        var manager = SystemShopManager.get();
        Probe buyer = new Probe(server.overworld(), "CreateReloadBuy");
        SystemShopCategory category = byName(CATEGORY);
        need(category != null && category.id.equals(state.getProperty("category")), "restart restores Chinese/color category with stable generated ID");
        check(manager.getCategories().size() == Integer.parseInt(state.getProperty("categoryCount")), "restart preserves category count without reseeding");
        check(byName(COMMAND_CATEGORY) != null && byName(OP_CATEGORY) != null && manager.getCategory("creation_legacy") != null,
                "restart restores command, zero-XP OP, and explicit-ID categories");
        check(manager.getCategories().values().stream().filter(c -> c.displayName.equals("重复显示名")).count() == 2,
                "restart restores distinct generated categories sharing display name");
        need(category.items.size() == 3, "restart has exactly the three accepted products, no invalid/duplicate/revoked drafts");
        SystemShopItem held = product(buyer, category, Items.BRICK, HELD_NAME);
        SystemShopItem selected = product(buyer, category, Items.AMETHYST_SHARD, INVENTORY_NAME);
        SystemShopItem command = product(buyer, category, Items.PRISMARINE_SHARD, COMMAND_NAME);
        need(held != null && selected != null && command != null, "restart restores all products by type and display name");
        check(held.id.equals(state.getProperty("held")) && selected.id.equals(state.getProperty("inventory"))
                && command.id.equals(state.getProperty("command")), "product IDs survive restart unchanged");
        check(held.buyPrice == 12 && held.sellPrice == 3 && selected.buyPrice == 10 && selected.sellPrice == 0
                && command.buyPrice == 9 && command.sellPrice == 2, "all accepted buy/sell prices survive restart");
        check(ItemStack.isSameItemSameComponents(template(buyer, held), sample(Items.BRICK, HELD_NAME, 1))
                && ItemStack.isSameItemSameComponents(template(buyer, selected), sample(Items.AMETHYST_SHARD, INVENTORY_NAME, 1)),
                "full held/inventory sample components survive serialization and restart");
        SystemShopGui.openCategoryView(buyer, category.id);
        check(buyer.button(HELD_NAME) >= 0 && buyer.button(INVENTORY_NAME) >= 0 && buyer.button("商城管理") < 0,
                "restarted regular buyer sees new products, not management");
        purchase(buyer, held, "restarted");
        buyer.closeContainer();
    }
}
