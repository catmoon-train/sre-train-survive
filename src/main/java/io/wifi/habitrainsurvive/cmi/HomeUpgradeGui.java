package io.wifi.habitrainsurvive.cmi;

import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.menu.MenuGui;
import io.wifi.habitrainsurvive.menu.MenuManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.Map;

public final class HomeUpgradeGui {
    private HomeUpgradeGui() { }
    public static void open(ServerPlayer player) {
        CmiManager manager = CmiManager.get();
        PlayerProfileData profile = manager.getOrCreateProfile(player);
        int limit = manager.getMaxHomesForPlayer(player.getUUID());
        if (limit == Integer.MAX_VALUE) { MessageUtil.sendError(player, "家额度", "已达到家额度安全上限，无法继续购买。"); return; }
        int purchased = profile.purchasedHomeSlots;
        long price = manager.nextHomeSlotPrice(player.getUUID());
        MenuGui.openSlots(player, "购买家上限 · 本次 +1", 3, Map.of(
                4, new MenuGui.Button(MenuGui.icon(Items.RED_BED, "当前家数量 " + profile.homes.size() + " / " + limit,
                        "基础/权限组额度: " + manager.getBaseMaxHomesForPlayer(player.getUUID()),
                        "已永久购买额外额度: " + purchased,
                        "购买价格: 100、500、900、1300……每次增加400",
                        "删除家不退额度或金币，也不会重置购买次数"), p -> { }),
                11, new MenuGui.Button(MenuGui.icon(Items.EMERALD, "确认购买 +1 家上限",
                        "本次花费: " + EconomyManager.get().format(price),
                        "购买后上限: " + (limit + 1),
                        "额外额度永久保存，并与权限组额度叠加"), p -> {
                    if (manager.purchaseHomeSlot(p, purchased, price)) MenuManager.get().open(p, "homes");
                    else open(p);
                }),
                15, new MenuGui.Button(MenuGui.icon(Items.RED_CONCRETE, "取消购买，不扣费"), p -> MenuManager.get().open(p, "homes"))));
    }
}
