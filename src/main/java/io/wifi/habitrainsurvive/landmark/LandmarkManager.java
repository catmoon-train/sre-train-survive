package io.wifi.habitrainsurvive.landmark;

import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.cmi.WarpPoint;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import io.wifi.habitrainsurvive.world.SafeTeleportService;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Consumer;

public final class LandmarkManager {
    private LandmarkManager() { }
    public static boolean admin(ServerPlayer player) { return PermissionManager.get().hasPermission(player, "habi.landmark.admin"); }
    public static Map<String, WarpPoint> all() { return CmiManager.get().getWarps(); }
    public static boolean exists(String key, WarpPoint point) { return point != null && all().get(key) == point; }
    public static boolean editable(ServerPlayer player, String key, WarpPoint point) { return admin(player) && exists(key, point); }
    public static String id(String input) {
        String key = input.trim().toLowerCase(java.util.Locale.ROOT);
        if (!key.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("ID只允许1到32位小写字母、数字、下划线和短横线");
        return key;
    }
    public static void update(ServerPlayer player, String key, WarpPoint point, Consumer<WarpPoint> change) {
        if (!editable(player, key, point)) throw new IllegalArgumentException("没有权限或地标已更新，请刷新");
        WarpPoint next = new WarpPoint(point.name, point.pos == null ? null : point.pos.copy(), point.cost, point.creator);
        next.description = point.description; next.icon = point.icon; next.enabled = point.enabled;
        change.accept(next);
        if (next.name == null || next.name.isBlank() || !Double.isFinite(next.cost) || next.cost < 0 || next.cost > 1e12)
            throw new IllegalArgumentException("地标名称或费用无效");
        all().put(key, next); ServerDataManager.saveAll();
    }
    public static boolean teleport(ServerPlayer player, String key) {
        if (!PermissionManager.get().hasPermission(player, "habi.cmi.warp")) {
            MessageUtil.sendError(player, "地标", "没有公共地标传送权限"); return false;
        }
        WarpPoint point = all().get(key);
        if (point == null || !point.enabled || point.pos == null || !Double.isFinite(point.cost) || point.cost < 0) {
            MessageUtil.sendError(player, "地标", "该地标不存在、未开放或配置无效"); return false;
        }
        if (!EconomyManager.get().hasBalance(player.getUUID(), point.cost)) {
            MessageUtil.sendError(player, "地标", "金币不足，费用为 " + EconomyManager.get().format(point.cost)); return false;
        }
        GlobalPosInfo target = point.pos.copy(); double cost = point.cost;
        player.closeContainer();
        TeleportWarmupManager.get().scheduleTeleport(player, "地标 " + point.name, () -> SafeTeleportService.get().queuePosition(player,
                target, cost, "Landmark " + key, () -> exists(key, point) && point.enabled
                        && PermissionManager.get().hasPermission(player, "habi.cmi.warp"),
                p -> MessageUtil.sendSuccess(p, "地标", "已到达 " + point.name)));
        return true;
    }
}
