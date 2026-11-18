package io.wifi.habitrainsurvive.redeem;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.util.ItemSerializer;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RedeemManager {
    private static final RedeemManager INSTANCE = new RedeemManager();
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static class Storage {
        public Map<String, RedeemCode> codes = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();

    public static RedeemManager get() {
        return INSTANCE;
    }

    public void load(Path path, HolderLookup.Provider registries) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.codes == null) {
            this.storage.codes = new ConcurrentHashMap<>();
        }
        // 默认内置开服福利兑换码 VIP2026 与 HABI666
        if (!storage.codes.containsKey("HABI666")) {
            List<String> items = List.of(
                    ItemSerializer.serialize(new ItemStack(Items.DIAMOND, 5), registries),
                    ItemSerializer.serialize(new ItemStack(Items.GOLDEN_APPLE, 3), registries)
            );
            storage.codes.put("HABI666", new RedeemCode("HABI666", 9999, 888.0, items, "", 8760));
        }
        if (!storage.codes.containsKey("VIP2026")) {
            List<String> items = List.of(
                    ItemSerializer.serialize(new ItemStack(Items.NETHERITE_INGOT, 1), registries)
            );
            storage.codes.put("VIP2026", new RedeemCode("VIP2026", 500, 1500.0, items, "vip", 8760));
        }
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    public Map<String, RedeemCode> getCodes() {
        return storage.codes;
    }

    public void putCode(RedeemCode rc) {
        storage.codes.put(rc.code.toUpperCase(Locale.ROOT), rc);
    }

    public RedeemCode getCode(String code) {
        return storage.codes.get(code.toUpperCase(Locale.ROOT));
    }

    public RedeemCode removeCode(String code) {
        return storage.codes.remove(code.toUpperCase(Locale.ROOT));
    }

    public List<String> generateBatchCodes(String prefix, int count, int maxUsesEach, double coins, List<String> items, String grantGroup, long durationHours) {
        List<String> generated = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder(prefix.toUpperCase(Locale.ROOT)).append("-");
            for (int j = 0; j < 8; j++) {
                sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
            }
            String codeStr = sb.toString();
            putCode(new RedeemCode(codeStr, maxUsesEach, coins, items, grantGroup, durationHours));
            generated.add(codeStr);
        }
        return generated;
    }

    public synchronized boolean redeem(ServerPlayer player, String rawCode) {
        String key = rawCode.trim().toUpperCase(Locale.ROOT);
        RedeemCode rc = storage.codes.get(key);
        if (rc == null) {
            MessageUtil.sendError(player, "兑换码", "兑换码 [" + key + "] 不存在或输入有误！");
            MessageUtil.playError(player);
            return false;
        }
        if (rc.isExpired()) {
            MessageUtil.sendError(player, "兑换码", "该兑换码已过期失效！");
            MessageUtil.playError(player);
            return false;
        }
        if (rc.usedBy.contains(player.getUUID())) {
            MessageUtil.sendError(player, "兑换码", "您已经使用过该兑换码了，每人限领一次！");
            MessageUtil.playError(player);
            return false;
        }
        if (rc.getRemainingUses() <= 0) {
            MessageUtil.sendError(player, "兑换码", "来晚啦！该兑换码已被抢光 (剩余次数: 0)！");
            MessageUtil.playError(player);
            return false;
        }

        rc.usedBy.add(player.getUUID());
        List<String> rewardDesc = new ArrayList<>();
        if (rc.rewardCoins > 0) {
            EconomyManager.get().deposit(player.getUUID(), rc.rewardCoins, "Redeem CDK " + key);
            rewardDesc.add(EconomyManager.get().format(rc.rewardCoins));
        }
        if (rc.serializedItems != null) {
            for (String json : rc.serializedItems) {
                ItemStack stack = ItemSerializer.deserialize(json, player.server.registryAccess());
                if (!stack.isEmpty()) {
                    rewardDesc.add(stack.getHoverName().getString() + " x" + stack.getCount());
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                }
            }
        }
        if (rc.grantGroup != null && !rc.grantGroup.isBlank()) {
            if (PermissionManager.get().setPlayerGroup(player.getUUID(), rc.grantGroup)) {
                rewardDesc.add("会员权限组 [" + rc.grantGroup.toUpperCase(Locale.ROOT) + "]");
            }
        }

        MessageUtil.sendSuccess(player, "兑换码", "🎊 兑换码 [" + key + "] 激活成功！获得奖励: " + String.join(" + ", rewardDesc));
        MessageUtil.playDing(player);
        return true;
    }
}
