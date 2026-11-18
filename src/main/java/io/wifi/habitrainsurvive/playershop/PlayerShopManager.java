package io.wifi.habitrainsurvive.playershop;

import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.chestshop.ShopInventory;
import io.wifi.habitrainsurvive.chestshop.ShopTradeRules;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.*;

/** All mutations execute synchronously on the server thread and revalidate their GUI snapshots. */
public final class PlayerShopManager {
    private static final PlayerShopManager INSTANCE = new PlayerShopManager();
    public static PlayerShopManager get() { return INSTANCE; }
    public static final class Storage {
        public PlayerShopConfig config = new PlayerShopConfig();
        public Map<UUID, PlayerShop> shops = new LinkedHashMap<>();
    }
    private Storage storage = new Storage();
    private Path dataPath;
    private long configRevision;

    public synchronized void load(Path path) {
        dataPath = path;
        storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (storage.shops == null) storage.shops = new LinkedHashMap<>();
        for (PlayerShop shop : storage.shops.values()) if (shop.listings == null) shop.listings = new LinkedHashMap<>();
        // Never trim existing stock to fit a new/invalid configuration.
        if (storage.config == null || !storage.config.valid(List.of())) {
            storage.config = new PlayerShopConfig();
            HabiTrainSurvive.LOGGER.warn("电子商店配置无效，已使用默认限制；原库存完整保留。");
        }
        configRevision++;
    }
    public synchronized void save(Path path) { ServerDataManager.writeJson(path, storage); }
    public void saveChanges() { if (dataPath != null) save(dataPath); }
    public PlayerShopConfig config() { return storage.config.copy(); }
    public long configRevision() { return configRevision; }
    public PlayerShop getShop(UUID owner) { return storage.shops.get(owner); }
    public List<PlayerShop> shops() { return List.copyOf(storage.shops.values()); }
    public boolean current(PlayerShop shop) { return shop != null && getShop(shop.ownerUuid) == shop; }
    private static boolean active(ServerPlayer p) { return p.isAlive() && !p.isSpectator(); }
    public boolean owns(ServerPlayer p, PlayerShop shop) { return active(p) && current(shop) && p.getUUID().equals(shop.ownerUuid); }
    public boolean listingCurrent(PlayerShop shop, PlayerShop.Listing listing, long revision) {
        return current(shop) && shop.current(listing, revision);
    }

    public synchronized boolean openShop(ServerPlayer p, long quoteRevision) {
        if (!active(p) || getShop(p.getUUID()) != null || quoteRevision != configRevision) return fail(p, "开店报价已失效，请刷新。");
        double cost = storage.config.openingCost;
        if (!ShopTradeRules.price(cost, true) || !EconomyManager.get().withdraw(p.getUUID(), cost, "PlayerShop Open")) return fail(p, "开店付款失败。");
        PlayerShop shop = new PlayerShop();
        shop.ownerUuid = p.getUUID();
        shop.ownerName = p.getGameProfile().getName();
        storage.shops.put(shop.ownerUuid, shop);
        persistTransaction();
        return true;
    }
    public synchronized boolean upgrade(ServerPlayer p, PlayerShop shop, int quotedLevel, long quoteRevision) {
        if (!owns(p, shop) || shop.level != quotedLevel || shop.level >= 5 || quoteRevision != configRevision)
            return fail(p, "升级报价已失效。");
        double cost = storage.config.level(shop.level + 1).upgradeCost();
        if (!EconomyManager.get().withdraw(p.getUUID(), cost, "PlayerShop Upgrade L" + (shop.level + 1))) return fail(p, "升级付款失败。");
        shop.level++;
        persistTransaction();
        return true;
    }

    /** Only a real inventory slot is a source. The frozen GUI's display stack is never consumed. */
    public synchronized boolean deposit(ServerPlayer p, PlayerShop shop, int slot, ItemStack expected, int amount, double price) {
        if (!owns(p, shop) || slot < 0 || slot >= 36 || amount <= 0 || expected.isEmpty()) return fail(p, "入库请求失效。");
        ItemStack source = p.getInventory().getItem(slot);
        if (!ItemStack.isSameItemSameComponents(source, expected) || source.getCount() < amount) return fail(p, "原背包物品或数量已变化。");
        PlayerShop.Listing listing = null;
        for (PlayerShop.Listing candidate : shop.listings.values()) {
            ItemStack template = ItemSerializer.deserialize(candidate.serializedItem, p.server.registryAccess());
            if (!template.isEmpty() && ItemStack.isSameItemSameComponents(template, source)) { listing = candidate; break; }
        }
        if (!shop.canDeposit(storage.config, listing == null, amount)) return fail(p, "仓库容量或商品种类上限不足，请先升级。");
        if (listing == null) {
            if (!ShopTradeRules.price(price, false)) return fail(p, "单价必须至少为 0.01，且为有限数值。");
            String json = ItemSerializer.serialize(source.copyWithCount(1), p.server.registryAccess());
            if (json.isBlank()) return fail(p, "无法保存完整物品组件，未移动物品。");
            listing = new PlayerShop.Listing();
            listing.serializedItem = json;
            listing.displayName = source.getHoverName().getString();
            listing.unitPrice = price;
            shop.listings.put(listing.id, listing);
        }
        p.getInventory().removeItem(slot, amount);
        p.getInventory().setChanged();
        listing.quantity += amount;
        listing.revision++;
        persistTransaction();
        return true;
    }
    public synchronized boolean withdraw(ServerPlayer p, PlayerShop shop, PlayerShop.Listing listing, long revision, int amount) {
        if (!owns(p, shop) || !listingCurrent(shop, listing, revision) || amount <= 0 || amount > listing.quantity)
            return fail(p, "库存或权限已变化，请刷新。");
        ItemStack template = ItemSerializer.deserialize(listing.serializedItem, p.server.registryAccess());
        if (template.isEmpty() || ShopInventory.space(p.getInventory(), template) < amount) return fail(p, "背包空间不足或物品数据不可用。");
        listing.quantity -= amount;
        listing.revision++;
        ShopInventory.insert(p.getInventory(), template, amount);
        persistTransaction();
        return true;
    }
    public synchronized boolean setPrice(ServerPlayer p, PlayerShop shop, PlayerShop.Listing listing, long revision, double price) {
        if (!owns(p, shop) || !listingCurrent(shop, listing, revision) || !ShopTradeRules.price(price, false)) return fail(p, "价格无效或商品已变化。");
        listing.unitPrice = price;
        listing.revision++;
        saveChanges();
        return true;
    }
    public synchronized boolean removeListing(ServerPlayer p, PlayerShop shop, PlayerShop.Listing listing, long revision) {
        if (!owns(p, shop) || !listingCurrent(shop, listing, revision) || listing.quantity != 0)
            return fail(p, "请先取出全部库存，才能移除商品。");
        shop.listings.remove(listing.id);
        saveChanges();
        return true;
    }
    public synchronized boolean buy(ServerPlayer p, PlayerShop shop, PlayerShop.Listing listing, long revision, int amount) {
        if (!active(p) || !listingCurrent(shop, listing, revision) || p.getUUID().equals(shop.ownerUuid)) return fail(p, "商品已变化或不能购买自己的商品。");
        double total = ShopTradeRules.total(listing.unitPrice, amount);
        ItemStack template = ItemSerializer.deserialize(listing.serializedItem, p.server.registryAccess());
        if (total <= 0 || amount > listing.quantity || template.isEmpty() || ShopInventory.space(p.getInventory(), template) < amount)
            return fail(p, "库存不足、数量无效或背包空间不足。");
        boolean bought = shop.purchase(p.getUUID(), listing, revision, amount,
                () -> EconomyManager.get().transfer(p.getUUID(), shop.ownerUuid, total, "PlayerShop Buy " + listing.displayName),
                () -> ShopInventory.insert(p.getInventory(), template, amount));
        if (!bought) return fail(p, "付款失败，库存和背包未变更。");
        persistTransaction();
        MessageUtil.sendSuccess(p, "电子商店", "购买 " + amount + " 件，支付 " + EconomyManager.get().format(total));
        return true;
    }
    public synchronized boolean updateConfig(ServerPlayer p, PlayerShopConfig candidate, long expectedRevision) {
        if (!p.hasPermissions(2) || expectedRevision != configRevision || candidate == null || !candidate.valid(storage.shops.values()))
            return fail(p, "配置无效/已变化，或新限制会使已有商店超额；库存未改动。");
        storage.config = candidate.copy();
        configRevision++;
        saveChanges();
        return true;
    }
    private void persistTransaction() {
        // Main lifecycle wiring includes this manager in saveAll, together with the economy.
        ServerDataManager.saveAll();
    }
    private static boolean fail(ServerPlayer p, String message) { MessageUtil.sendError(p, "电子商店", message); return false; }
}
