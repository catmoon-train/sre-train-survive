package io.wifi.habitrainsurvive.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import io.wifi.habitrainsurvive.auction.AuctionManager;
import io.wifi.habitrainsurvive.chestshop.ChestShopManager;
import io.wifi.habitrainsurvive.claim.ClaimManager;
import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.config.ModuleConfig;
import io.wifi.habitrainsurvive.config.ModuleId;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.gift.GiftManager;
import io.wifi.habitrainsurvive.grave.GraveManager;
import io.wifi.habitrainsurvive.lottery.LotteryManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.quest.QuestManager;
import io.wifi.habitrainsurvive.redeem.RedeemManager;
import io.wifi.habitrainsurvive.sysshop.SystemShopManager;
import io.wifi.habitrainsurvive.title.TitleManager;
import io.wifi.habitrainsurvive.town.TownManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ServerDataManager {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Path dataDir;
    private static MinecraftServer currentServer;
    private static int tickCounter = 0;

    public static void init(MinecraftServer server) {
        currentServer = server;
        dataDir = FabricLoader.getInstance().getConfigDir().resolve(HabiTrainSurvive.MOD_ID);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            HabiTrainSurvive.LOGGER.error("无法创建配置与数据目录: {}", dataDir, e);
        }
        if (ModuleConfig.isEnabled(ModuleId.PERMISSION)) PermissionManager.get().load(dataDir.resolve("permissions.json"));
        if (ModuleConfig.isEnabled(ModuleId.ECONOMY)) EconomyManager.get().load(dataDir.resolve("economy.json"));
        if (ModuleConfig.isEnabled(ModuleId.CLAIM)) ClaimManager.get().load(dataDir.resolve("claims.json"));
        if (ModuleConfig.isEnabled(ModuleId.TOWN)) TownManager.get().load(dataDir.resolve("towns.json"));
        if (ModuleConfig.isEnabled(ModuleId.CMI)) CmiManager.get().load(dataDir.resolve("cmi.json"));
        if (ModuleConfig.isEnabled(ModuleId.CHEST_SHOP)) ChestShopManager.get().load(dataDir.resolve("chestshops.json"));
        if (ModuleConfig.isEnabled(ModuleId.AUCTION)) AuctionManager.get().load(dataDir.resolve("auctions.json"));
        if (ModuleConfig.isEnabled(ModuleId.GIFT)) GiftManager.get().load(dataDir.resolve("gifts.json"), server.registryAccess());
        if (ModuleConfig.isEnabled(ModuleId.REDEEM)) RedeemManager.get().load(dataDir.resolve("redeem_codes.json"), server.registryAccess());
        if (ModuleConfig.isEnabled(ModuleId.LOTTERY)) LotteryManager.get().load(dataDir.resolve("lottery.json"), server.registryAccess());
        if (ModuleConfig.isEnabled(ModuleId.SYSTEM_SHOP)) SystemShopManager.get().load(dataDir.resolve("system_shop.json"), server.registryAccess());
        if (ModuleConfig.isEnabled(ModuleId.GRAVE)) GraveManager.get().load(dataDir.resolve("graves.json"));
        if (ModuleConfig.isEnabled(ModuleId.QUEST)) QuestManager.get().load(dataDir.resolve("quests.json"), dataDir.resolve("quest_progress.json"));
        if (ModuleConfig.isEnabled(ModuleId.TITLE)) TitleManager.get().load(dataDir.resolve("titles.json"));
        if (ModuleConfig.isEnabled(ModuleId.REDPACKET)) {
            io.wifi.habitrainsurvive.redpacket.RedPacketManager.get().load(dataDir.resolve("redpackets.json"));
            io.wifi.habitrainsurvive.redpacket.RedPacketManager.get().tick(server);
        }
        io.wifi.habitrainsurvive.CoreRuntime.started(server, dataDir);
        HabiTrainSurvive.LOGGER.info("已成功加载已启用的 HabiTrainSurvive 子系统数据。");
    }

    /** 供 /habi reloadall 使用：就地重读本模组全部数据文件，不重启服务器。 */
    public static java.util.List<String> reloadAll(MinecraftServer server) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        if (dataDir == null) { errors.add("数据目录尚未初始化"); return errors; }
        currentServer = server;
        reload(errors, "modules.json", () -> {
            try { ModuleConfig.reload(); } catch (IOException e) { throw new RuntimeException(e.getMessage(), e); }
        });
        if (ModuleConfig.isEnabled(ModuleId.PERMISSION)) reload(errors, "permissions.json", () -> PermissionManager.get().load(dataDir.resolve("permissions.json")));
        if (ModuleConfig.isEnabled(ModuleId.ECONOMY)) reload(errors, "economy.json", () -> EconomyManager.get().load(dataDir.resolve("economy.json")));
        if (ModuleConfig.isEnabled(ModuleId.CLAIM)) reload(errors, "claims.json", () -> ClaimManager.get().load(dataDir.resolve("claims.json")));
        if (ModuleConfig.isEnabled(ModuleId.TOWN)) reload(errors, "towns.json", () -> TownManager.get().load(dataDir.resolve("towns.json")));
        if (ModuleConfig.isEnabled(ModuleId.CMI)) reload(errors, "cmi.json", () -> CmiManager.get().load(dataDir.resolve("cmi.json")));
        if (ModuleConfig.isEnabled(ModuleId.CHEST_SHOP)) reload(errors, "chestshops.json", () -> ChestShopManager.get().load(dataDir.resolve("chestshops.json")));
        if (ModuleConfig.isEnabled(ModuleId.AUCTION)) reload(errors, "auctions.json", () -> AuctionManager.get().load(dataDir.resolve("auctions.json")));
        if (ModuleConfig.isEnabled(ModuleId.GIFT)) reload(errors, "gifts.json", () -> GiftManager.get().load(dataDir.resolve("gifts.json"), server.registryAccess()));
        if (ModuleConfig.isEnabled(ModuleId.REDEEM)) reload(errors, "redeem_codes.json", () -> RedeemManager.get().load(dataDir.resolve("redeem_codes.json"), server.registryAccess()));
        if (ModuleConfig.isEnabled(ModuleId.LOTTERY)) reload(errors, "lottery.json", () -> LotteryManager.get().load(dataDir.resolve("lottery.json"), server.registryAccess()));
        if (ModuleConfig.isEnabled(ModuleId.SYSTEM_SHOP)) reload(errors, "system_shop.json", () -> SystemShopManager.get().load(dataDir.resolve("system_shop.json"), server.registryAccess()));
        if (ModuleConfig.isEnabled(ModuleId.GRAVE)) reload(errors, "graves.json", () -> GraveManager.get().load(dataDir.resolve("graves.json")));
        if (ModuleConfig.isEnabled(ModuleId.QUEST)) reload(errors, "quests.json", () -> {
            String problem = QuestManager.get().reload();
            if (problem != null) throw new IllegalArgumentException(problem);
        });
        if (ModuleConfig.isEnabled(ModuleId.TITLE)) reload(errors, "titles.json", () -> TitleManager.get().load(dataDir.resolve("titles.json")));
        if (ModuleConfig.isEnabled(ModuleId.REDPACKET)) reload(errors, "redpackets.json", () -> io.wifi.habitrainsurvive.redpacket.RedPacketManager.get().load(dataDir.resolve("redpackets.json")));
        errors.addAll(io.wifi.habitrainsurvive.CoreRuntime.reloadConfiguration());
        return errors;
    }

    private static void reload(java.util.List<String> errors, String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            HabiTrainSurvive.LOGGER.error("重载 {} 失败，继续使用内存中的有效数据: {}", name, message);
            errors.add(name + ": " + message);
        }
    }

    public static void saveAll() {
        if (dataDir == null) return;
        if (ModuleConfig.isEnabled(ModuleId.PERMISSION)) PermissionManager.get().save(dataDir.resolve("permissions.json"));
        if (ModuleConfig.isEnabled(ModuleId.ECONOMY)) EconomyManager.get().save(dataDir.resolve("economy.json"));
        if (ModuleConfig.isEnabled(ModuleId.CLAIM)) ClaimManager.get().save(dataDir.resolve("claims.json"));
        if (ModuleConfig.isEnabled(ModuleId.TOWN)) TownManager.get().save(dataDir.resolve("towns.json"));
        if (ModuleConfig.isEnabled(ModuleId.CMI)) CmiManager.get().save(dataDir.resolve("cmi.json"));
        if (ModuleConfig.isEnabled(ModuleId.CHEST_SHOP)) ChestShopManager.get().save(dataDir.resolve("chestshops.json"));
        if (ModuleConfig.isEnabled(ModuleId.AUCTION)) AuctionManager.get().save(dataDir.resolve("auctions.json"));
        if (ModuleConfig.isEnabled(ModuleId.GIFT)) GiftManager.get().save(dataDir.resolve("gifts.json"));
        if (ModuleConfig.isEnabled(ModuleId.REDEEM)) RedeemManager.get().save(dataDir.resolve("redeem_codes.json"));
        if (ModuleConfig.isEnabled(ModuleId.LOTTERY)) LotteryManager.get().save(dataDir.resolve("lottery.json"));
        if (ModuleConfig.isEnabled(ModuleId.SYSTEM_SHOP)) SystemShopManager.get().save(dataDir.resolve("system_shop.json"));
        if (ModuleConfig.isEnabled(ModuleId.GRAVE)) GraveManager.get().save(dataDir.resolve("graves.json"));
        if (ModuleConfig.isEnabled(ModuleId.QUEST)) QuestManager.get().save(dataDir.resolve("quest_progress.json"));
        if (ModuleConfig.isEnabled(ModuleId.TITLE)) TitleManager.get().save(dataDir.resolve("titles.json"));
        if (ModuleConfig.isEnabled(ModuleId.REDPACKET)) io.wifi.habitrainsurvive.redpacket.RedPacketManager.get().save(dataDir.resolve("redpackets.json"));
        io.wifi.habitrainsurvive.CoreRuntime.save();
    }

    public static void onServerTick(MinecraftServer server) {
        currentServer = server;
        tickCounter++;
        io.wifi.habitrainsurvive.CoreRuntime.tick(server);
        if (tickCounter % 20 == 0) {
            if (ModuleConfig.isEnabled(ModuleId.CMI)) CmiManager.get().tick(server);
            if (ModuleConfig.isEnabled(ModuleId.AUCTION)) AuctionManager.get().tick(server);
            if (ModuleConfig.isEnabled(ModuleId.TOWN)) TownManager.get().tick(server);
            if (ModuleConfig.isEnabled(ModuleId.CLAIM)) ClaimManager.get().tickPlayerRegions(server);
            io.wifi.habitrainsurvive.CoreRuntime.tickSecond(server);
            if (ModuleConfig.isEnabled(ModuleId.QUEST)) QuestManager.get().tick(server);
            if (ModuleConfig.isEnabled(ModuleId.REDPACKET)) io.wifi.habitrainsurvive.redpacket.RedPacketManager.get().tick(server);
        }
        if (tickCounter >= 20 * 300) {
            tickCounter = 0;
            saveAll();
        }
    }

    public static MinecraftServer getServer() {
        return currentServer;
    }

    public static <T> T readJson(Path file, Class<T> clazz, T defaultValue) {
        if (!Files.exists(file)) {
            return defaultValue;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T obj = GSON.fromJson(reader, clazz);
            return obj != null ? obj : defaultValue;
        } catch (Exception e) {
            HabiTrainSurvive.LOGGER.error("读取数据文件失败: {}", file, e);
            return defaultValue;
        }
    }

    public static void writeJson(Path file, Object data) {
        try {
            Files.createDirectories(file.getParent());
            Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
                if (Files.exists(tempFile)) {
                    Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
            }
            HabiTrainSurvive.LOGGER.error("保存数据文件失败: {}", file, e);
        }
    }
}
