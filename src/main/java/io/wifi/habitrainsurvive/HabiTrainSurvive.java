package io.wifi.habitrainsurvive;

import io.wifi.habitrainsurvive.auction.AuctionCommands;
import io.wifi.habitrainsurvive.chestshop.ChestShopCommands;
import io.wifi.habitrainsurvive.chestshop.ChestShopListener;
import io.wifi.habitrainsurvive.claim.ClaimCommands;
import io.wifi.habitrainsurvive.claim.ClaimProtectionListener;
import io.wifi.habitrainsurvive.cmi.CmiCommands;
import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyCommands;
import io.wifi.habitrainsurvive.gift.GiftCommands;
import io.wifi.habitrainsurvive.grave.GraveCommands;
import io.wifi.habitrainsurvive.lottery.LotteryCommands;
import io.wifi.habitrainsurvive.permission.PermissionCommands;
import io.wifi.habitrainsurvive.quest.QuestCommands;
import io.wifi.habitrainsurvive.quest.QuestManager;
import io.wifi.habitrainsurvive.redeem.RedeemCommands;
import io.wifi.habitrainsurvive.sysshop.SystemShopCommands;
import io.wifi.habitrainsurvive.title.TitleCommands;
import io.wifi.habitrainsurvive.town.TownCommands;
import io.wifi.habitrainsurvive.trade.TradeCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HabiTrainSurvive implements ModInitializer {
    public static final String MOD_ID = "habi_train_survive";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        // 0. 加载模块化开关配置（默认全部启用）
        ModuleConfig.load();

        // 1. 注册服务端生命周期与持久化定时任务
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerDataManager.init(server);
            if (ModuleConfig.isEnabled(ModuleId.ECONOMY)) {
                io.wifi.habitrainsurvive.economy.VirtualProxyEconomyApi.start(server,
                        net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(MOD_ID));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            io.wifi.habitrainsurvive.economy.VirtualProxyEconomyApi.stop();
            io.wifi.habitrainsurvive.quest.QuestWebEditor.stop();
            ServerDataManager.saveAll();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CoreRuntime.stopped());
        ServerTickEvents.END_SERVER_TICK.register(ServerDataManager::onServerTick);

        // 2. 注册事件监听器 (领地与城镇保护、CMI、箱子商店、每日任务监听)
        CmiManager.get().registerEvents();
        CoreRuntime.registerEvents();
        // Shop interactions must precede generic claim-container checks; shop creation rechecks ownership itself.
        ChestShopListener.register();
        ClaimProtectionListener.register();
        QuestManager.get().registerEvents();

        // 3. 按模块开关注册各子系统命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var helpBefore = io.wifi.habitrainsurvive.help.CommandHelpIndex.snapshot(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.PERMISSION)) PermissionCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.ECONOMY)) EconomyCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.CLAIM)) ClaimCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.TOWN)) TownCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.CMI)) CmiCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.TRADE)) TradeCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.CHEST_SHOP)) ChestShopCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.AUCTION)) AuctionCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.GIFT)) GiftCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.REDEEM)) RedeemCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.REDPACKET)) io.wifi.habitrainsurvive.redpacket.RedPacketCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.LOTTERY)) LotteryCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.SYSTEM_SHOP)) SystemShopCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.GRAVE)) GraveCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.QUEST)) QuestCommands.register(dispatcher);
            if (ModuleConfig.isEnabled(ModuleId.TITLE)) TitleCommands.register(dispatcher);
            CoreRuntime.registerCommands(dispatcher);
            io.wifi.habitrainsurvive.help.CommandHelpCommands.finish(helpBefore, dispatcher);
        });

        long enabledCount = java.util.Arrays.stream(ModuleId.values()).filter(ModuleConfig::isEnabled).count();
        LOGGER.info("Habi Train Survive v4.0 服务端套件初始化完成！已启用 {}/{} 个模块。", enabledCount, ModuleId.values().length);
    }
}
