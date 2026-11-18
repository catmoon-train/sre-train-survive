package io.wifi.habitrainsurvive.sysshop;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.economy.ExperienceCost;
import io.wifi.habitrainsurvive.economy.ExperiencePayment;
import io.wifi.habitrainsurvive.chestshop.ShopInventory;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.time.ZoneId;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class SystemShopManager {
    private static final SystemShopManager INSTANCE = new SystemShopManager();

    public static class Storage {
        public Map<String, SystemShopCategory> categories = new LinkedHashMap<>();
        public String purchaseTimeZone = "Asia/Shanghai";
        public ShopPurchaseLedger purchases = new ShopPurchaseLedger();
    }

    private Storage storage = new Storage();
    private Path savePath;

    public static SystemShopManager get() {
        return INSTANCE;
    }

    public synchronized void load(Path path, HolderLookup.Provider registries) {
        savePath = path;
        boolean firstRun = !Files.exists(path);
        storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (storage.categories == null) storage.categories = new LinkedHashMap<>();
        if (storage.purchaseTimeZone == null || storage.purchaseTimeZone.isBlank()) storage.purchaseTimeZone = "Asia/Shanghai";
        ZoneId.of(storage.purchaseTimeZone);
        if (storage.purchases == null) storage.purchases = new ShopPurchaseLedger();
        storage.purchases.migrate();
        Set<String> itemIds = new HashSet<>();
        storage.categories.values().removeIf(c -> c == null);
        Map<String, SystemShopCategory> normalized = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        storage.categories.forEach((key, category) -> {
            if (key == null || key.isBlank()) return;
            category.id = key.toLowerCase(Locale.ROOT);
            if (category.displayName == null) category.displayName = category.id;
            if (category.iconItemId == null) category.iconItemId = "minecraft:chest";
            if (category.purchaseLimits == null) category.purchaseLimits = new ShopPurchasePolicy();
            category.purchaseLimits.migrate();
            if (category.items == null) category.items = new ArrayList<>();
            category.items.removeIf(item -> item == null);
            category.items.forEach(item -> {
                item.migrate(now);
                if (!itemIds.add(item.id)) throw new IllegalArgumentException("商城商品 ID 重复，请检查配置：" + item.id);
            });
            normalized.put(category.id, category);
        });
        storage.categories = normalized;
        // Seed only a new store. Deleted categories/items must stay deleted after restart.
        if (firstRun) ensureDefaultCategories(registries);
        saveChanges();
    }

    public synchronized void save(Path path) {
        savePath = path;
        ServerDataManager.writeJson(path, storage);
    }

    public synchronized void saveChanges() {
        if (savePath != null) ServerDataManager.writeJson(savePath, storage);
    }

    private void saveTrade() {
        saveChanges();
        if (savePath != null) EconomyManager.get().save(savePath.resolveSibling("economy.json"));
    }

    private void ensureDefaultCategories(HolderLookup.Provider registries) {
        if (!storage.categories.containsKey("building")) {
            SystemShopCategory c = new SystemShopCategory("building", "§a建筑方块专区", "minecraft:oak_log");
            c.items.add(entry(Items.OAK_LOG, 4.0, 1.5, registries));
            c.items.add(entry(Items.SPRUCE_LOG, 4.0, 1.5, registries));
            c.items.add(entry(Items.STONE, 2.0, 0.5, registries));
            c.items.add(entry(Items.COBBLESTONE, 1.0, 0.2, registries));
            c.items.add(entry(Items.GLASS, 3.0, 0.8, registries));
            c.items.add(entry(Items.QUARTZ_BLOCK, 12.0, 4.0, registries));
            c.items.add(entry(Items.SEA_LANTERN, 25.0, 8.0, registries));
            c.items.add(entry(Items.GLOWSTONE, 20.0, 6.0, registries));
            storage.categories.put("building", c);
        }
        if (!storage.categories.containsKey("ores")) {
            SystemShopCategory c = new SystemShopCategory("ores", "§b珍稀矿产专区", "minecraft:diamond");
            c.items.add(entry(Items.COAL, 6.0, 2.0, registries));
            c.items.add(entry(Items.COPPER_INGOT, 9.0, 3.0, registries));
            c.items.add(entry(Items.IRON_INGOT, 24.0, 8.0, registries));
            c.items.add(entry(Items.GOLD_INGOT, 45.0, 15.0, registries));
            c.items.add(entry(Items.REDSTONE, 8.0, 2.5, registries));
            c.items.add(entry(Items.LAPIS_LAZULI, 9.0, 3.0, registries));
            c.items.add(entry(Items.EMERALD, 100.0, 35.0, registries));
            c.items.add(entry(Items.DIAMOND, 240.0, 80.0, registries));
            c.items.add(entry(Items.NETHERITE_INGOT, 1500.0, 450.0, registries));
            storage.categories.put("ores", c);
        }
        if (!storage.categories.containsKey("farming")) {
            SystemShopCategory c = new SystemShopCategory("farming", "§e农牧食品专区", "minecraft:golden_apple");
            c.items.add(entry(Items.WHEAT, 4.0, 1.2, registries));
            c.items.add(entry(Items.CARROT, 3.5, 1.0, registries));
            c.items.add(entry(Items.POTATO, 3.5, 1.0, registries));
            c.items.add(entry(Items.SUGAR_CANE, 3.5, 1.0, registries));
            c.items.add(entry(Items.PUMPKIN, 6.0, 2.0, registries));
            c.items.add(entry(Items.COOKED_BEEF, 12.0, 3.0, registries));
            c.items.add(entry(Items.GOLDEN_CARROT, 25.0, 6.0, registries));
            c.items.add(entry(Items.GOLDEN_APPLE, 120.0, 30.0, registries));
            storage.categories.put("farming", c);
        }
        if (!storage.categories.containsKey("combat")) {
            SystemShopCategory c = new SystemShopCategory("combat", "§c战斗与奇珍专区", "minecraft:totem_of_undying");
            c.items.add(entry(Items.EXPERIENCE_BOTTLE, 35.0, 8.0, registries));
            c.items.add(entry(Items.ENDER_PEARL, 30.0, 8.0, registries));
            c.items.add(entry(Items.BLAZE_ROD, 40.0, 12.0, registries));
            c.items.add(entry(Items.GHAST_TEAR, 80.0, 25.0, registries));
            c.items.add(entry(Items.SHULKER_SHELL, 350.0, 100.0, registries));
            c.items.add(entry(Items.TOTEM_OF_UNDYING, 1200.0, 300.0, registries));
            c.items.add(entry(Items.ELYTRA, 8000.0, 2000.0, registries));
            storage.categories.put("combat", c);
        }
    }

    private SystemShopItem entry(Item item, double buyPrice, double sellPrice, HolderLookup.Provider registries) {
        ItemStack st = new ItemStack(item, 1);
        return new SystemShopItem(
                ItemSerializer.serialize(st, registries),
                st.getHoverName().getString(),
                buyPrice,
                sellPrice
        );
    }

    public Map<String, SystemShopCategory> getCategories() {
        return Collections.unmodifiableMap(storage.categories);
    }

    public SystemShopCategory getCategory(String id) {
        return id == null ? null : storage.categories.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean isCurrent(SystemShopCategory category) {
        return category != null && storage.categories.get(category.id) == category;
    }

    public boolean isCurrent(SystemShopCategory category, SystemShopItem item) {
        return isCurrent(category) && item != null && category.items.contains(item);
    }

    public boolean containsItem(SystemShopItem item) {
        return item != null && storage.categories.values().stream().anyMatch(c -> c.items.contains(item));
    }

    public static boolean authorized(ServerPlayer player) {
        return PermissionManager.get().hasPermission(player, "habi.shop.admin")
                || PermissionManager.get().hasPermission(player, "habi.admin");
    }

    public static boolean authorized(net.minecraft.commands.CommandSourceStack source) {
        return source.hasPermission(2) || source.getPlayer() != null && authorized(source.getPlayer());
    }

    private void requireAdmin(ServerPlayer player) {
        if (!authorized(player)) throw new IllegalArgumentException("您不再拥有商城管理权限");
    }

    private void requireCategory(SystemShopCategory category) {
        if (!isCurrent(category)) throw new IllegalArgumentException("分类已被删除或重新加载，请重新打开菜单");
    }

    private void requireItem(SystemShopCategory category, SystemShopItem item) {
        requireCategory(category);
        if (!isCurrent(category, item)) throw new IllegalArgumentException("商品已被删除，请重新打开菜单");
    }

    public synchronized void addCategory(ServerPlayer admin, String id) {
        requireAdmin(admin);
        id = ShopEditValues.id(id);
        if (storage.categories.containsKey(id)) throw new IllegalArgumentException("分类 ID 已存在");
        storage.categories.put(id, new SystemShopCategory(id, id, "minecraft:chest"));
        saveChanges();
    }

    /** The GUI accepts a display name; stable machine IDs are generated rather than imposed on the operator. */
    public synchronized SystemShopCategory createCategory(ServerPlayer admin, String displayName) {
        requireAdmin(admin);
        String name = ShopEditValues.name(displayName);
        String id;
        do { id = "category_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
        while (storage.categories.containsKey(id));
        SystemShopCategory category = new SystemShopCategory(id, name, "minecraft:chest");
        storage.categories.put(id, category); saveChanges(); return category;
    }

    public synchronized void editCategory(ServerPlayer admin, SystemShopCategory category, String name, String icon) {
        requireAdmin(admin);
        requireCategory(category);
        String validated = ShopEditValues.name(name);
        var id = net.minecraft.resources.ResourceLocation.tryParse(icon);
        if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)
                || net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id) == Items.AIR)
            throw new IllegalArgumentException("无效物品 ID");
        category.displayName = validated;
        category.iconItemId = id.toString();
        saveChanges();
    }

    public synchronized void deleteCategory(ServerPlayer admin, SystemShopCategory category) {
        requireAdmin(admin);
        requireCategory(category);
        storage.categories.remove(category.id);
        saveChanges();
    }

    public synchronized SystemShopItem addItem(ServerPlayer admin, SystemShopCategory category, ItemStack stack, double buy, double sell) {
        requireAdmin(admin);
        requireCategory(category);
        if (stack.isEmpty()) throw new IllegalArgumentException("请选择非空物品");
        SystemShopPricing.money(buy);
        SystemShopPricing.money(sell);
        ItemStack single = stack.copyWithCount(1);
        String serialized = ItemSerializer.serialize(single, admin.server.registryAccess());
        if (serialized.isBlank()) throw new IllegalArgumentException("物品序列化失败");
        SystemShopItem item = new SystemShopItem(serialized, single.getHoverName().getString(), buy, sell);
        item.pricing.validate(buy, sell);
        if (hasDuplicate(admin, item, single)) throw new IllegalArgumentException("同组件物品已上架，请编辑现有商品以避免跨商品套利");
        category.items.add(item);
        saveChanges();
        return item;
    }

    public synchronized void editItem(ServerPlayer admin, SystemShopCategory category, SystemShopItem item,
                                      Consumer<SystemShopItem> edit) {
        requireAdmin(admin);
        requireItem(category, item);
        SystemShopItem draft = item.copy();
        edit.accept(draft);
        draft.displayName = ShopEditValues.name(draft.displayName);
        draft.pricing.validate(draft.buyPrice, draft.sellPrice);
        if (draft.experiencePrice == null) throw new IllegalArgumentException("经验价格不能为空");
        draft.experiencePrice.validate();
        if (draft.purchaseLimits == null) throw new IllegalArgumentException("限购配置不能为空");
        draft.purchaseLimits.validate();
        if (!java.util.Objects.equals(item.id, draft.id)) throw new IllegalArgumentException("商品 ID 不允许修改");
        // Recover under the OLD policy before changing half-life or bounds.
        long now = System.currentTimeMillis();
        double pressure = DynamicShopPrice.recovered(item, now);
        item.buyPrice = draft.buyPrice;
        item.sellPrice = draft.sellPrice;
        item.displayName = draft.displayName;
        item.pricing = draft.pricing.copy();
        item.pressure = item.dynamicEnabled == draft.dynamicEnabled ? DynamicShopPrice.clampPressure(item.pricing, pressure) : 0;
        item.dynamicEnabled = draft.dynamicEnabled;
        item.purchaseLimits = draft.purchaseLimits.copy();
        item.experiencePrice = draft.experiencePrice.copy();
        item.pressureUpdatedAt = Math.max(now, item.pressureUpdatedAt);
        saveChanges();
    }

    public synchronized void resetPressure(ServerPlayer admin, SystemShopCategory category, SystemShopItem item) {
        requireAdmin(admin);
        requireItem(category, item);
        item.pressure = 0;
        item.pressureUpdatedAt = System.currentTimeMillis();
        saveChanges();
    }

    public synchronized void deleteItem(ServerPlayer admin, SystemShopCategory category, SystemShopItem item) {
        requireAdmin(admin);
        requireItem(category, item);
        category.items.remove(item);
        saveChanges();
    }

    private boolean hasDuplicate(ServerPlayer player, SystemShopItem item, ItemStack template) {
        for (SystemShopCategory category : storage.categories.values()) {
            for (SystemShopItem other : category.items) {
                if (other != item && ItemStack.isSameItemSameComponents(template,
                        ItemSerializer.deserialize(other.serializedItem, player.server.registryAccess()))) return true;
            }
        }
        return false;
    }

    private ItemStack tradeTemplate(ServerPlayer player, SystemShopItem item) {
        if (!containsItem(item)) throw new IllegalArgumentException("商品已下架，请重新打开商城");
        ItemStack template = ItemSerializer.deserialize(item.serializedItem, player.server.registryAccess());
        if (template.isEmpty()) throw new IllegalArgumentException("此商品数据无效，请联系管理员");
        if (hasDuplicate(player, item, template)) throw new IllegalArgumentException("检测到重复上架，暂停交易；请管理员删除重复商品");
        return template.copyWithCount(1);
    }

    public SystemShopCategory categoryOf(SystemShopItem item) {
        return storage.categories.values().stream().filter(category -> category.items.contains(item)).findFirst().orElse(null);
    }

    public boolean dynamicEnabled(SystemShopCategory category, SystemShopItem item) {
        return category != null && item != null && category.dynamicEnabled && item.dynamicEnabled;
    }

    public String priceMode(SystemShopCategory category, SystemShopItem item) {
        if (!category.dynamicEnabled) return "固定基准价（分类动态总开关关闭）";
        return item.dynamicEnabled ? "动态价格" : "固定基准价（单品动态关闭）";
    }

    public DynamicShopPrice.Quote quote(SystemShopItem item, boolean buy, int count, long now) {
        SystemShopCategory category = categoryOf(item);
        if (category == null) throw new IllegalArgumentException("商品已下架，请刷新");
        return DynamicShopPrice.quote(item, buy, count, now, dynamicEnabled(category, item));
    }

    public synchronized void setCategoryDynamic(ServerPlayer admin, SystemShopCategory category, boolean enabled) {
        requireAdmin(admin); requireCategory(category);
        if (category.dynamicEnabled == enabled) return;
        category.dynamicEnabled = enabled;
        long now = System.currentTimeMillis();
        for (SystemShopItem item : category.items) {
            item.pressure = 0; item.pressureUpdatedAt = Math.max(now, item.pressureUpdatedAt);
        }
        saveChanges();
    }

    public synchronized void moveItem(ServerPlayer admin, SystemShopCategory source, SystemShopItem item, SystemShopCategory target) {
        requireAdmin(admin); requireItem(source, item); requireCategory(target);
        if (source == target) throw new IllegalArgumentException("商品已经在该分类中");
        source.items.remove(item); target.items.add(item); saveChanges();
    }

    public ZoneId purchaseZone() { return ZoneId.of(storage.purchaseTimeZone); }

    public synchronized void setPurchaseTimeZone(ServerPlayer admin, String zone) {
        requireAdmin(admin);
        try { storage.purchaseTimeZone = ZoneId.of(zone.trim()).getId(); }
        catch (java.time.DateTimeException e) { throw new IllegalArgumentException("无效时区，请使用 Asia/Shanghai、UTC 等有效ID"); }
        saveChanges();
    }

    public synchronized void editCategoryLimits(ServerPlayer admin, SystemShopCategory category, Consumer<ShopPurchasePolicy> change) {
        requireAdmin(admin); requireCategory(category);
        ShopPurchasePolicy draft = category.purchaseLimits.copy(); change.accept(draft); draft.validate();
        category.purchaseLimits = draft.copy(); saveChanges();
    }

    private static String categoryKey(SystemShopCategory category) { return "category:" + category.id; }
    private static String itemKey(SystemShopItem item) { return "item:" + item.id; }

    public synchronized void resetPurchaseUsage(ServerPlayer admin, SystemShopCategory category, SystemShopItem item) {
        requireAdmin(admin); requireCategory(category);
        if (item != null) requireItem(category, item);
        storage.purchases.reset(item == null ? categoryKey(category) : itemKey(item)); saveChanges();
    }

    public String purchaseProblem(ServerPlayer player, SystemShopItem item, int count, long now) {
        SystemShopCategory category = categoryOf(item);
        if (category == null) return "商品已下架";
        String problem = storage.purchases.problem(player.getUUID(), categoryKey(category), category.purchaseLimits, count, now, purchaseZone());
        if (problem != null) return "分类【" + category.displayName + "】：" + problem;
        problem = storage.purchases.problem(player.getUUID(), itemKey(item), item.purchaseLimits, count, now, purchaseZone());
        return problem == null ? null : "商品【" + item.displayName + "】：" + problem;
    }

    public ShopPurchaseLedger.Remaining remaining(ServerPlayer player, SystemShopCategory category, SystemShopItem item, boolean shared, long now) {
        requireCategory(category);
        if (item != null) requireItem(category, item);
        ShopPurchasePolicy policy = item == null ? category.purchaseLimits : item.purchaseLimits;
        return storage.purchases.remaining(player.getUUID(), item == null ? categoryKey(category) : itemKey(item),
                shared ? policy.global : policy.personal, shared, now, purchaseZone());
    }

    public int maxPurchasable(ServerPlayer player, SystemShopCategory category, SystemShopItem item, int stackSize, long now) {
        long result = stackSize;
        for (boolean shared : new boolean[]{false, true}) {
            result = Math.min(result, remaining(player, category, null, shared, now).maximum());
            result = Math.min(result, remaining(player, category, item, shared, now).maximum());
        }
        return (int) Math.max(0, result);
    }

    private static String left(long count) { return count < 0 ? "不限" : String.valueOf(count); }
    private static String summary(ShopPurchaseLedger.Remaining limits) {
        if (limits.maximum() == Long.MAX_VALUE) return "不限购";
        return "单次" + left(limits.perTransaction()) + " / 日" + left(limits.daily()) + " / 周" + left(limits.weekly())
                + " / 月" + left(limits.monthly()) + " / 累计" + left(limits.total());
    }

    public List<String> purchaseQuotaLore(ServerPlayer player, SystemShopCategory category, SystemShopItem item, long now) {
        return List.of("分类个人剩余: " + summary(remaining(player, category, null, false, now)),
                "分类全服剩余: " + summary(remaining(player, category, null, true, now)),
                "单品个人剩余: " + summary(remaining(player, category, item, false, now)),
                "单品全服剩余: " + summary(remaining(player, category, item, true, now)),
                "重置时区: " + purchaseZone().getId() + " · 周一0点刷新周额度", "按件计数；卖回不返还购买额度");
    }

    public int countSellable(ServerPlayer player, SystemShopItem item) {
        ItemStack template = ItemSerializer.deserialize(item.serializedItem, player.server.registryAccess());
        if (template.isEmpty()) return 0;
        return player.getInventory().items.stream()
                .filter(stack -> ItemStack.isSameItemSameComponents(stack, template)).mapToInt(ItemStack::getCount).sum();
    }

    public synchronized void buyItem(ServerPlayer player, SystemShopItem item, int count) {
        buyItem(player, item, count, false);
    }

    /** Experience is a fixed alternate price; it never changes the coin demand curve. */
    public synchronized void buyItemWithExperience(ServerPlayer player, SystemShopItem item, int count) {
        buyItem(player, item, count, true);
    }

    private void buyItem(ServerPlayer player, SystemShopItem item, int count, boolean useExperience) {
        try {
            ItemStack template = tradeTemplate(player, item);
            if (count < 1 || count > template.getMaxStackSize()) throw new IllegalArgumentException("每次最多购买一组");
            long now = System.currentTimeMillis();
            String limitProblem = purchaseProblem(player, item, count, now);
            if (limitProblem != null) throw new IllegalArgumentException(limitProblem);
            if (ShopInventory.space(player.getInventory(), template) < count) throw new IllegalArgumentException("背包空间不足，未扣款或占用额度");
            DynamicShopPrice.Quote quote = null;
            if (useExperience) {
                String experienceProblem = ExperiencePayment.problem(player, item.experiencePrice, count);
                if (experienceProblem != null) throw new IllegalArgumentException(experienceProblem);
            } else {
                quote = quote(item, true, count, now);
                double balance = EconomyManager.get().getBalance(player.getUUID());
                if (!Double.isFinite(balance) || balance < 0 || balance > 1_000_000_000_000.0)
                    throw new IllegalArgumentException("余额数据无效或超过安全上限，请联系管理员");
                if (!EconomyManager.get().hasBalance(player.getUUID(), quote.total()))
                    throw new IllegalArgumentException("金币不足，需要 " + EconomyManager.get().format(quote.total()));
            }
            if (useExperience) {
                if (!ExperiencePayment.pay(player, item.experiencePrice, count))
                    throw new IllegalArgumentException("经验余额已变化，请刷新后重试");
            } else if (!EconomyManager.get().withdraw(player.getUUID(), quote.total(), "SystemShop Buy " + item.displayName + " x" + count)) {
                throw new IllegalArgumentException("扣款失败，请稍后重试");
            }
            ShopInventory.insert(player.getInventory(), template, count);
            SystemShopCategory category = categoryOf(item);
            storage.purchases.record(player.getUUID(), List.of(categoryKey(category), itemKey(item)), count, now, purchaseZone());
            if (!useExperience) DynamicShopPrice.apply(item, quote);
            player.getInventory().setChanged();
            saveTrade();
            String paid = useExperience ? item.experiencePrice.label(count) : EconomyManager.get().format(quote.total());
            MessageUtil.sendSuccess(player, "系统商城", "购买 " + count + "x [" + item.displayName + "]，花费 " + paid);
            MessageUtil.playDing(player);
        } catch (IllegalArgumentException error) {
            MessageUtil.sendError(player, "系统商城", error.getMessage());
            MessageUtil.playError(player);
        }
    }

    public synchronized void sellItem(ServerPlayer player, SystemShopItem item, boolean sellAllInInventory) {
        try {
            ItemStack template = tradeTemplate(player, item);
            int available = countSellable(player, item);
            if (available == 0) throw new IllegalArgumentException("背包中没有相同物品（组件必须完全一致）");
            int count = sellAllInInventory ? available : 1;
            DynamicShopPrice.Quote quote = quote(item, false, count, System.currentTimeMillis());
            if (quote.cents() == 0) throw new IllegalArgumentException("逐件回收价不足 0.01，请等待价格恢复或联系管理员");
            double balance = EconomyManager.get().getBalance(player.getUUID());
            if (!Double.isFinite(balance) || balance + quote.total() > 1_000_000_000_000.0)
                throw new IllegalArgumentException("余额已达到安全上限");
            int remaining = count;
            for (ItemStack stack : player.getInventory().items) {
                if (remaining == 0) break;
                if (ItemStack.isSameItemSameComponents(stack, template)) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.shrink(take);
                    remaining -= take;
                }
            }
            EconomyManager.get().deposit(player.getUUID(), quote.total(), "SystemShop Sell " + item.displayName + " x" + count);
            DynamicShopPrice.apply(item, quote);
            player.getInventory().setChanged();
            saveTrade();
            MessageUtil.sendSuccess(player, "系统商城", "出售 " + count + "x [" + item.displayName + "]，获得 " + EconomyManager.get().format(quote.total()));
            MessageUtil.playDing(player);
        } catch (IllegalArgumentException error) {
            MessageUtil.sendError(player, "系统商城", error.getMessage());
            MessageUtil.playError(player);
        }
    }
}
