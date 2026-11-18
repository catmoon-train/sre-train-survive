package io.wifi.habitrainsurvive.chestshop;

import io.wifi.habitrainsurvive.claim.Claim;
import io.wifi.habitrainsurvive.claim.ClaimManager;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.hub.HubProtectionListener;
import io.wifi.habitrainsurvive.town.Town;
import io.wifi.habitrainsurvive.town.TownManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import io.wifi.habitrainsurvive.world.WorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChestShopManager {
    private static final ChestShopManager INSTANCE = new ChestShopManager();
    private static final String IDENTITY_KEY = "habitrainsurvive:chest_shop";
    public static class Storage {
        public Map<String, ChestShop> shopsByPosKey = new ConcurrentHashMap<>();
    }
    private Storage storage = new Storage();
    private Path dataPath;
    private final Map<UUID, String> lastSelectedShopKey = new ConcurrentHashMap<>();
    public static ChestShopManager get() { return INSTANCE; }

    public void load(Path path) {
        dataPath = path;
        storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (storage.shopsByPosKey == null) storage.shopsByPosKey = new ConcurrentHashMap<>();
        storage.shopsByPosKey.values().forEach(s -> { if (s.staff == null) s.staff = new HashSet<>(); });
        lastSelectedShopKey.clear();
    }
    public void save(Path path) { ServerDataManager.writeJson(path, storage); }
    public void saveChanges() { if (dataPath != null) save(dataPath); }
    public ChestShop getShopAt(String dim, BlockPos pos) {
        ChestShop direct = getShopByKey(ChestShop.posKey(dim, pos.getX(), pos.getY(), pos.getZ()));
        if (direct != null) return direct;
        // A chest merged after creation must not expose the protected half through its new neighbour.
        var server = ServerDataManager.getServer();
        if (server == null) return null;
        ServerLevel level = new GlobalPosInfo(dim, pos.getX(), pos.getY(), pos.getZ(), 0, 0).resolveLevel(server);
        if (level == null || !level.hasChunkAt(pos)) return null;
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            return getShopByKey(ChestShop.posKey(dim, other.getX(), other.getY(), other.getZ()));
        }
        return null;
    }
    public ChestShop getShopByKey(String key) { return key == null ? null : storage.shopsByPosKey.get(key); }
    public boolean isCurrent(ChestShop shop) { return shop != null && getShopByKey(shop.getPosKey()) == shop; }
    public void putShop(ChestShop shop) { storage.shopsByPosKey.putIfAbsent(shop.getPosKey(), shop); saveChanges(); }
    public ChestShop removeShop(String key) {
        ChestShop old = storage.shopsByPosKey.remove(key);
        if (old != null) {
            lastSelectedShopKey.values().removeIf(key::equals);
            var server = ServerDataManager.getServer();
            if (server != null) {
                ServerLevel level = new GlobalPosInfo(old.dimension, old.x, old.y, old.z, 0, 0).resolveLevel(server);
                BlockPos pos = new BlockPos(old.x, old.y, old.z);
                if (level != null && level.hasChunkAt(pos)) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null && old.id.equals(marker(be))) mark(be, null);
                }
            }
            saveChanges();
        }
        return old;
    }
    public Collection<ChestShop> getAllShops() { return List.copyOf(storage.shopsByPosKey.values()); }
    public List<ChestShop> searchShops(String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        return getAllShops().stream().filter(s -> s.itemDisplayName != null
                && s.itemDisplayName.toLowerCase(Locale.ROOT).contains(lower)).toList();
    }
    public void setLastSelectedShop(UUID uuid, String key) { lastSelectedShopKey.put(uuid, key); }
    public ChestShop getLastSelectedShop(UUID uuid) { return getShopByKey(lastSelectedShopKey.get(uuid)); }

    public boolean nearby(ServerPlayer player, String dim, BlockPos pos) {
        return player.isAlive() && !player.isSpectator()
                && player.serverLevel().dimension().location().toString().equals(dim)
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= 36;
    }
    public static boolean supported(BlockEntity be) {
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity)) return false;
        // Each shop owns exactly one physical container. Never lock/take over the other half of a double chest.
        return !be.getBlockState().hasProperty(ChestBlock.TYPE)
                || be.getBlockState().getValue(ChestBlock.TYPE) == ChestType.SINGLE;
    }
    public static String containerType(BlockEntity be) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString();
    }
    private static String marker(BlockEntity be) {
        CustomData data = be.components().get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getString(IDENTITY_KEY);
    }
    private static void mark(BlockEntity be, String id) {
        CustomData old = be.components().get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = old == null ? new CompoundTag() : old.copyTag();
        if (id == null) tag.remove(IDENTITY_KEY); else tag.putString(IDENTITY_KEY, id);
        be.setComponents(DataComponentMap.builder().addAll(be.components()).set(DataComponents.CUSTOM_DATA, CustomData.of(tag)).build());
        be.setChanged();
    }
    public Container resolveContainer(ServerPlayer context, ChestShop shop) {
        if (!isCurrent(shop)) return null;
        ServerLevel level = new GlobalPosInfo(shop.dimension, shop.x, shop.y, shop.z, 0, 0).resolveLevel(context.server);
        BlockPos pos = new BlockPos(shop.x, shop.y, shop.z);
        if (level == null || !level.hasChunkAt(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (!supported(be) || !(be instanceof Container c)) return null;
        String type = containerType(be);
        if (shop.containerType != null && !shop.containerType.equals(type)) return null;
        if (!shop.identityBound) {
            // Legacy shops bind only during a local owner/admin visit, never from an arbitrary customer's GUI.
            if ((!context.getUUID().equals(shop.ownerUuid) && !context.hasPermissions(2)) || !nearby(context, shop.dimension, pos)) return null;
            Claim claim = ClaimManager.get().getClaimAt(shop.dimension, pos);
            if (claim != null && !Objects.equals(shop.ownerUuid, claim.ownerUuid)) return null;
            if (!marker(be).isEmpty() && !Objects.equals(shop.id, marker(be))) return null;
            mark(be, shop.id);
            shop.containerType = type;
            shop.identityBound = true;
            saveChanges();
        }
        if (!Objects.equals(shop.id, marker(be))) return null;
        return c;
    }

    /** Strict ownership, not merely container trust, is required to turn a container into a shop. */
    public boolean canCreate(ServerPlayer player, String dim, BlockPos pos, BlockEntity expected, boolean explain) {
        String error = null;
        if (!nearby(player, dim, pos) || player.serverLevel().getBlockEntity(pos) != expected || !supported(expected))
            error = "请靠近原来的单箱、木桶或潜影盒（双箱请先拆分）。";
        else if (getShopAt(dim, pos) != null) error = "该容器已经是商店，不能接管。";
        if (error == null && player.server.isUnderSpawnProtection(player.serverLevel(), pos, player))
            error = "出生点保护区域不能创建商店。";
        if (error == null && HubProtectionListener.buildDenied(player.serverLevel(), player))
            error = "世界建造保护不允许建立商店。";
        if (error == null && !WorldManager.get().assetAllowed(player, dim, true)) return false;
        Claim claim = ClaimManager.get().getClaimAt(dim, pos);
        Town town = TownManager.get().getTownAt(dim, pos);
        if (error == null && io.wifi.habitrainsurvive.plot.PlotGrid.WORLD.equals(dim) && claim == null)
            error = "只能在自己拥有的地皮内部创建商店，道路和未售地皮不可建店。";
        if (error == null && claim != null && !player.getUUID().equals(claim.ownerUuid))
            error = "只能在自己拥有的领地/地块创建商店，容器信任不代表所有权。";
        if (error == null && town != null) {
            var plot = town.claimedChunks.get(Town.chunkKeyAt(dim, pos));
            boolean ownPlot = plot != null && player.getUUID().equals(plot.ownerUuid);
            boolean ownTown = player.getUUID().equals(town.mayorUuid) && (plot == null || plot.ownerUuid == null);
            if ((!ownPlot && !ownTown) || !town.canBuild(player.getUUID(), pos)) error = "请在自己的城镇地块创建商店。";
        }
        // No provenance exists for wilderness containers. Refuse populated ones instead of seizing somebody's stock.
        if (error == null && claim == null && town == null && !((Container) expected).isEmpty())
            error = "无主荒野只能使用空容器创建商店；请先在自己的领地建店。";
        if (error != null && explain) MessageUtil.sendError(player, "箱子商店", error);
        return error == null;
    }

    public synchronized ChestShop create(ServerPlayer player, String dim, BlockPos pos, BlockEntity expected,
                                         ItemStack template, double buy, double sell) {
        if (!ShopTradeRules.price(buy, true) || !ShopTradeRules.price(sell, true) || buy == 0 && sell == 0
                || template.isEmpty() || !canCreate(player, dim, pos, expected, true)) return null;
        ItemStack unit = template.copyWithCount(1);
        String json = ItemSerializer.serialize(unit, player.server.registryAccess());
        if (json.isBlank()) return null;
        ChestShop shop = new ChestShop(UUID.randomUUID().toString(), player.getUUID(), player.getGameProfile().getName(),
                dim, pos.getX(), pos.getY(), pos.getZ(), json, unit.getHoverName().getString(), buy, sell);
        shop.containerType = containerType(expected);
        mark(expected, shop.id);
        shop.identityBound = true;
        putShop(shop);
        setLastSelectedShop(player.getUUID(), shop.getPosKey());
        return shop;
    }

    public boolean canManage(ServerPlayer player, ChestShop shop) {
        return isCurrent(shop) && (shop.canManage(player.getUUID()) || player.hasPermissions(2));
    }
    public boolean canDelete(ServerPlayer player, ChestShop shop) {
        return isCurrent(shop) && (player.getUUID().equals(shop.ownerUuid) || player.hasPermissions(2));
    }
    public synchronized boolean setPrices(ServerPlayer player, ChestShop shop, double buy, double sell) {
        if (!canManage(player, shop) || !ShopTradeRules.price(buy, true) || !ShopTradeRules.price(sell, true)
                || buy == 0 && sell == 0) return false;
        shop.buyPrice = buy;
        shop.sellPrice = sell;
        saveChanges();
        return true;
    }
    public synchronized boolean delete(ServerPlayer player, ChestShop shop) {
        if (!canDelete(player, shop)) return false;
        removeShop(shop.getPosKey());
        return true;
    }
    public int countStock(Container c, ItemStack t) { return ShopInventory.count(c, t); }
    public int countFreeSpace(Container c, ItemStack t) { return ShopInventory.space(c, t); }

    private boolean canTrade(ServerPlayer player, ChestShop shop, Container expected) {
        if (!isCurrent(shop) || player.getUUID().equals(shop.ownerUuid) || shop.ownerUuid == null
                || !nearby(player, shop.dimension, new BlockPos(shop.x, shop.y, shop.z))) return false;
        Container actual = resolveContainer(player, shop);
        if (actual == null || expected != null && actual != expected) return false;
        if (!WorldManager.get().assetAllowed(player, shop.dimension, true)) return false;
        var policy = HubProtectionListener.policy(player.serverLevel());
        if (policy != null && !HubProtectionListener.bypass(player)
                && (!policy.interact || !policy.build && !player.getMainHandItem().isEmpty())) return false;
        Claim claim = ClaimManager.get().getClaimAt(shop.dimension, new BlockPos(shop.x, shop.y, shop.z));
        Town town = TownManager.get().getTownAt(shop.dimension, new BlockPos(shop.x, shop.y, shop.z));
        if (claim != null && !Objects.equals(shop.ownerUuid, claim.ownerUuid)) return false;
        if (town != null) {
            var plot = town.claimedChunks.get(Town.chunkKeyAt(shop.dimension, new BlockPos(shop.x, shop.y, shop.z)));
            if (plot != null && plot.ownerUuid != null && !Objects.equals(shop.ownerUuid, plot.ownerUuid)) return false;
        }
        // A shop is a public trade endpoint, not permission to open its inventory or build in the town.
        return player.hasPermissions(2) || claim == null || claim.canEnter(player.getUUID());
    }
    public void buyFromShop(ServerPlayer player, ChestShop shop, int amount) { buyFromShop(player, shop, amount, null); }
    public synchronized void buyFromShop(ServerPlayer player, ChestShop shop, int amount, Container expected) {
        double total = shop == null ? -1 : ShopTradeRules.total(shop.buyPrice, amount);
        if (total <= 0 || !canTrade(player, shop, expected)) { fail(player, "交易已失效：不能自购，请靠近原容器并确认权限。"); return; }
        ItemStack t = ItemSerializer.deserialize(shop.serializedItem, player.server.registryAccess());
        Container box = resolveContainer(player, shop);
        if (t.isEmpty() || ShopInventory.space(player.getInventory(), t) < amount
                || !shop.adminInfinite && ShopInventory.count(box, t) < amount) { fail(player, "库存不足或背包空间不足。"); return; }
        boolean paid = shop.adminInfinite ? EconomyManager.get().withdraw(player.getUUID(), total, "AdminShop Buy")
                : EconomyManager.get().transfer(player.getUUID(), shop.ownerUuid, total, "ChestShop Buy");
        if (!paid) { fail(player, "付款失败，物品未移动。"); return; }
        if (!shop.adminInfinite) ShopInventory.remove(box, t, amount);
        ShopInventory.insert(player.getInventory(), t, amount);
        ServerDataManager.saveAll();
        MessageUtil.sendSuccess(player, "箱子商店", "已购买 " + amount + " 件，花费 " + EconomyManager.get().format(total));
    }
    public void sellToShop(ServerPlayer player, ChestShop shop, int amount) { sellToShop(player, shop, amount, null); }
    public synchronized void sellToShop(ServerPlayer player, ChestShop shop, int amount, Container expected) {
        double total = shop == null ? -1 : ShopTradeRules.total(shop.sellPrice, amount);
        if (total <= 0 || !canTrade(player, shop, expected)) { fail(player, "交易已失效：不能自售，请靠近原容器并确认权限。"); return; }
        ItemStack t = ItemSerializer.deserialize(shop.serializedItem, player.server.registryAccess());
        Container box = resolveContainer(player, shop);
        if (t.isEmpty() || ShopInventory.count(player.getInventory(), t) < amount
                || !shop.adminInfinite && ShopInventory.space(box, t) < amount) { fail(player, "物品不足或商店空间不足。"); return; }
        if (!shop.adminInfinite && !EconomyManager.get().transfer(shop.ownerUuid, player.getUUID(), total, "ChestShop Sell")) {
            fail(player, "付款失败，物品未移动。"); return;
        }
        if (shop.adminInfinite) EconomyManager.get().deposit(player.getUUID(), total, "AdminShop Sell");
        ShopInventory.remove(player.getInventory(), t, amount);
        if (!shop.adminInfinite) ShopInventory.insert(box, t, amount);
        ServerDataManager.saveAll();
        MessageUtil.sendSuccess(player, "箱子商店", "已出售 " + amount + " 件，获得 " + EconomyManager.get().format(total));
    }
    private static void fail(ServerPlayer p, String message) { MessageUtil.sendError(p, "箱子商店", message); }
}
