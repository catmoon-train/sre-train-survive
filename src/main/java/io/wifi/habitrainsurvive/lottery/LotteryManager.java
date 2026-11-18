package io.wifi.habitrainsurvive.lottery;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.economy.ExperienceCost;
import io.wifi.habitrainsurvive.economy.ExperiencePayment;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LotteryManager {
    private static final LotteryManager INSTANCE = new LotteryManager();
    private static final Random RANDOM = new Random();

    public static class Storage {
        public Map<String, CratePool> pools = new ConcurrentHashMap<>();
        // PlayerUUID -> (PoolId -> CurrentPityCount)
        public Map<UUID, Map<String, Integer>> playerPity = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();
    private Path storagePath;

    public static LotteryManager get() {
        return INSTANCE;
    }

    public void load(Path path, HolderLookup.Provider registries) {
        this.storagePath = path;
        boolean firstRun = !Files.exists(path);
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.pools == null) this.storage.pools = new ConcurrentHashMap<>();
        if (this.storage.playerPity == null) this.storage.playerPity = new ConcurrentHashMap<>();
        storage.pools.values().forEach(CratePool::migrate);
        if (firstRun) ensureDefaultPools(registries);
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    public void saveChanges() {
        if (storagePath == null) throw new IllegalStateException("抽奖数据尚未加载");
        save(storagePath);
    }

    public void deletePool(CratePool pool) {
        if (!storage.pools.remove(pool.id, pool)) return;
        storage.playerPity.values().forEach(pity -> pity.remove(pool.id));
        saveChanges();
    }

    private void ensureDefaultPools(HolderLookup.Provider registries) {
        if (!storage.pools.containsKey("common")) {
            CratePool common = new CratePool("common", "§a普通幸运盲盒", 200.0, 30);
            common.prizes.add(makePrize(new ItemStack(Items.IRON_INGOT, 16), 0, 40, false, registries));
            common.prizes.add(makePrize(new ItemStack(Items.GOLD_INGOT, 8), 0, 30, false, registries));
            common.prizes.add(makePrize(new ItemStack(Items.GOLDEN_APPLE, 4), 0, 20, false, registries));
            common.prizes.add(makePrize(new ItemStack(Items.DIAMOND, 4), 100.0, 8, true, registries));
            common.prizes.add(makePrize(new ItemStack(Items.NETHERITE_INGOT, 1), 300.0, 2, true, registries));
            storage.pools.put("common", common);
        }
        if (!storage.pools.containsKey("rare")) {
            CratePool rare = new CratePool("rare", "§b史诗珍宝祈愿池", 1000.0, 40);
            rare.prizes.add(makePrize(new ItemStack(Items.DIAMOND, 8), 0, 35, false, registries));
            rare.prizes.add(makePrize(new ItemStack(Items.EMERALD, 16), 0, 30, false, registries));
            rare.prizes.add(makePrize(new ItemStack(Items.EXPERIENCE_BOTTLE, 64), 0, 20, false, registries));
            rare.prizes.add(makePrize(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2), 500.0, 10, true, registries));
            rare.prizes.add(makePrize(new ItemStack(Items.TOTEM_OF_UNDYING, 1), 800.0, 4, true, registries));
            rare.prizes.add(makePrize(new ItemStack(Items.NETHER_STAR, 1), 2000.0, 1, true, registries));
            storage.pools.put("rare", rare);
        }
        if (!storage.pools.containsKey("legendary")) {
            CratePool leg = new CratePool("legendary", "§6§l传说至尊神殿池", 5000.0, 50);
            leg.prizes.add(makePrize(new ItemStack(Items.DIAMOND_BLOCK, 4), 0, 35, false, registries));
            leg.prizes.add(makePrize(new ItemStack(Items.NETHERITE_INGOT, 2), 0, 25, false, registries));
            leg.prizes.add(makePrize(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 4), 1000.0, 20, false, registries));
            leg.prizes.add(makePrize(new ItemStack(Items.ELYTRA, 1), 3000.0, 10, true, registries));
            leg.prizes.add(makePrize(new ItemStack(Items.HEAVY_CORE, 1), 8000.0, 5, true, registries));
            leg.prizes.add(makePrize(new ItemStack(Items.NETHERITE_BLOCK, 2), 15000.0, 2, true, registries));
            storage.pools.put("legendary", leg);
        }
    }

    private CratePrize makePrize(ItemStack stack, double bonusCoins, int weight, boolean rare, HolderLookup.Provider registries) {
        return new CratePrize(
                ItemSerializer.serialize(stack, registries),
                stack.getHoverName().getString(),
                stack.getCount(),
                bonusCoins,
                weight,
                rare
        );
    }

    public Map<String, CratePool> getPools() {
        return storage.pools;
    }

    public CratePool getPool(String id) {
        return storage.pools.get(id.toLowerCase(Locale.ROOT));
    }

    public int getPlayerPity(UUID playerUuid, String poolId) {
        return storage.playerPity.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                .getOrDefault(poolId.toLowerCase(Locale.ROOT), 0);
    }

    private void setPlayerPity(UUID playerUuid, String poolId, int val) {
        storage.playerPity.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                .put(poolId.toLowerCase(Locale.ROOT), val);
    }

    public static ItemStack createCrateKeyItem(CratePool pool, int count) {
        ItemStack key = new ItemStack(Items.TRIPWIRE_HOOK, Math.max(1, count));
        key.set(DataComponents.CUSTOM_NAME, Component.literal("🔑 祈愿钥匙: [" + pool.displayName + "§r]").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        key.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("关联奖池ID: " + pool.id).withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("可在 /lottery 抽奖界面免费抵扣一次祈愿").withStyle(ChatFormatting.YELLOW)
        )));
        return key;
    }

    private boolean consumeCrateKeyIfPresent(ServerPlayer player, String poolId) {
        String marker = "关联奖池ID: " + poolId;
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack st = player.getInventory().items.get(i);
            if (!st.isEmpty() && st.is(Items.TRIPWIRE_HOOK)) {
                ItemLore lore = st.get(DataComponents.LORE);
                if (lore != null) {
                    for (Component line : lore.lines()) {
                        if (line.getString().contains(marker)) {
                            st.shrink(1);
                            if (st.isEmpty()) player.getInventory().items.set(i, ItemStack.EMPTY);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public synchronized void performDraw(ServerPlayer player, CratePool pool, int times) {
        performDraw(player, pool, times, false);
    }

    /** Direct experience draw, with the same key priority and pity logic as coin draws. */
    public synchronized void performDrawWithExperience(ServerPlayer player, CratePool pool, int times) {
        performDraw(player, pool, times, true);
    }

    private void performDraw(ServerPlayer player, CratePool pool, int times, boolean useExperience) {
        if (pool != null && getPool(pool.id) != pool) {
            MessageUtil.sendError(player, "幸运抽奖", "奖池已删除或更新，请重新打开菜单。");
            return;
        }
        if (pool == null || pool.prizes.isEmpty()) {
            MessageUtil.sendError(player, "幸运抽奖", "该奖池暂未配置奖品！");
            return;
        }
        if (pool.experienceDrawCost == null) pool.experienceDrawCost = new ExperienceCost();
        boolean usedKey = false;
        if (times == 1 && consumeCrateKeyIfPresent(player, pool.id)) {
            usedKey = true;
            MessageUtil.sendInfo(player, "幸运抽奖", "已自动消耗 1 把 [" + pool.displayName + "§e] 专属祈愿钥匙！");
        } else if (useExperience) {
            int multiplier = times == 10 ? 9 : times;
            String problem = ExperiencePayment.problem(player, pool.experienceDrawCost, multiplier);
            if (problem != null) {
                MessageUtil.sendError(player, "幸运抽奖", problem);
                MessageUtil.playError(player);
                return;
            }
            if (!ExperiencePayment.pay(player, pool.experienceDrawCost, multiplier)) {
                MessageUtil.sendError(player, "幸运抽奖", "经验已变化，请重新打开抽奖界面");
                MessageUtil.playError(player);
                return;
            }
            MessageUtil.sendInfo(player, "幸运抽奖", "消耗 " + pool.experienceDrawCost.label(multiplier) + " 进行祈愿。");
        } else {
            // 十连抽享受 9 折优惠
            double totalCost = times == 10 ? Math.round(pool.singleDrawCost * 9.0 * 100.0) / 100.0 : pool.singleDrawCost * times;
            if (!EconomyManager.get().withdraw(player.getUUID(), totalCost, "Lottery Draw " + pool.id + " x" + times)) {
                MessageUtil.sendError(player, "幸运抽奖", "金币不足！" + times + " 连抽需要花费 " + EconomyManager.get().format(totalCost));
                MessageUtil.playError(player);
                return;
            }
        }

        List<String> summary = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            int pity = getPlayerPity(player.getUUID(), pool.id) + 1;
            CratePrize won = rollOnePrize(pool, pity >= pool.pityThreshold);
            if (won.rareBroadcast) {
                setPlayerPity(player.getUUID(), pool.id, 0);
            } else {
                setPlayerPity(player.getUUID(), pool.id, pity);
            }

            ItemStack rewardStack = ItemSerializer.deserialize(won.serializedItem, player.server.registryAccess());
            if (!rewardStack.isEmpty()) {
                if (!player.getInventory().add(rewardStack)) {
                    player.drop(rewardStack, false);
                }
            }
            if (won.bonusCoins > 0) {
                EconomyManager.get().deposit(player.getUUID(), won.bonusCoins, "Lottery Bonus " + pool.id);
            }
            summary.add((won.rareBroadcast ? "§6★" : "§f") + won.displayName + " x" + won.count);

            if (won.rareBroadcast) {
                player.server.getPlayerList().broadcastSystemMessage(
                        MessageUtil.prefix("欧皇降临", ChatFormatting.GOLD)
                                .append(Component.literal("恭喜玩家 " + player.getName().getString() + " 在 [" + pool.displayName
                                        + "§e] 中抽中了稀有大奖 【" + won.displayName + " x" + won.count + "】"
                                        + (won.bonusCoins > 0 ? " 及彩金 " + EconomyManager.get().format(won.bonusCoins) : "") + "！").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)),
                        false
                );
            }
        }

        MessageUtil.sendSuccess(player, "幸运抽奖", "🎉 祈愿完成！本次获得: " + String.join("§7, ", summary));
        MessageUtil.playDing(player);
    }

    private CratePrize rollOnePrize(CratePool pool, boolean triggerPity) {
        if (triggerPity) {
            List<CratePrize> rares = pool.prizes.stream().filter(p -> p.rareBroadcast).toList();
            if (!rares.isEmpty()) {
                return rares.get(RANDOM.nextInt(rares.size()));
            }
        }
        int totalWeight = pool.getTotalWeight();
        int roll = RANDOM.nextInt(totalWeight);
        int cumulative = 0;
        for (CratePrize prize : pool.prizes) {
            cumulative += Math.max(1, prize.weight);
            if (roll < cumulative) {
                return prize;
            }
        }
        return pool.prizes.get(0);
    }
}
