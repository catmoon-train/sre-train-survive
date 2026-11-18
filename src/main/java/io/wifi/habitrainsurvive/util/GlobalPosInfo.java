package io.wifi.habitrainsurvive.util;

import io.wifi.habitrainsurvive.world.WorldManager;
import io.wifi.habitrainsurvive.world.SafeTeleportService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class GlobalPosInfo {
    public String dimension;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    /** Resource-world epoch. Older JSON defaults to generation zero. */
    public long generation;

    public GlobalPosInfo() {
    }

    public GlobalPosInfo(String dimension, double x, double y, double z, float yaw, float pitch) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        WorldManager manager = WorldManager.get();
        this.generation = manager == null ? 0 : manager.generation(dimension);
    }

    public static GlobalPosInfo fromPlayer(ServerPlayer player) {
        return new GlobalPosInfo(
                player.serverLevel().dimension().location().toString(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()
        );
    }

    public static GlobalPosInfo fromBlockPos(ServerLevel level, BlockPos pos) {
        return new GlobalPosInfo(
                level.dimension().location().toString(),
                pos.getX() + 0.5,
                pos.getY(),
                pos.getZ() + 0.5,
                0.0f,
                0.0f
        );
    }

    public BlockPos toBlockPos() {
        return BlockPos.containing(x, y, z);
    }

    public ServerLevel resolveLevel(MinecraftServer server) {
        if (dimension == null || dimension.isEmpty()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(dimension);
        if (rl == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, rl);
        ServerLevel target = server.getLevel(key);
        return target;
    }

    /** 返回独立坐标快照，保留代次，防止排队期间配置对象被修改。 */
    public GlobalPosInfo copy() {
        GlobalPosInfo copy = new GlobalPosInfo();
        copy.dimension = dimension; copy.x = x; copy.y = y; copy.z = z;
        copy.yaw = yaw; copy.pitch = pitch; copy.generation = generation;
        return copy;
    }

    /** 返回是否受理请求，不代表传送已完成；成功通知必须放在回调内。 */
    public boolean teleportAsync(ServerPlayer player) { return teleportAsync(player, p -> {}); }
    public boolean teleportAsync(ServerPlayer player, Consumer<ServerPlayer> success) {
        return teleportAsync(player, () -> true, success);
    }
    public boolean teleportAsync(ServerPlayer player, BooleanSupplier valid, Consumer<ServerPlayer> success) {
        return SafeTeleportService.get().queuePosition(player, this, 0, "定点传送", valid, success);
    }

    /** 同步兼容接口仅允许已准备的区块，不会为安全检查同步加载。 */
    public boolean teleport(ServerPlayer player) {
        return SafeTeleportService.position(player, this);
    }

    public String toShortString() {
        return String.format("%s (%.1f, %.1f, %.1f)", dimension, x, y, z);
    }
}
