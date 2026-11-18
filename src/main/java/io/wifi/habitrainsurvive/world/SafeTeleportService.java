package io.wifi.habitrainsurvive.world;

import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 非阻塞分批准备区块，再由服务端主线程验证落点并完成传送。 */
public final class SafeTeleportService {
    private static final SafeTeleportService INSTANCE = new SafeTeleportService();
    private final Map<UUID, Search> searches = new LinkedHashMap<>();
    private final Random random = new Random();
    private int cursor;
    private static final int MAX_CHUNK_PREPARATIONS = 4;
    private static final int MAX_SEARCH_TICKS = 1200;
    private static final long MAX_SEARCH_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final class Search {
        final UUID player;
        final String destination, origin;
        final Vec3 position;
        final float health;
        final long generation;
        final boolean rtp;
        GlobalPosInfo preferred;
        boolean fixed, internal;
        double cost;
        String reason;
        BooleanSupplier valid = () -> true;
        Consumer<ServerPlayer> onSuccess = p -> {};
        int attempts, age, x, z;
        final long startedNanos = System.nanoTime();
        PendingChunkPreparation chunk;
        void releaseChunk() {
            if (chunk != null) { chunk.close(); chunk = null; }
        }
        Search(ServerPlayer p, String id, boolean rtp) {
            player = p.getUUID(); destination = id; this.rtp = rtp;
            origin = p.serverLevel().dimension().location().toString(); position = p.position(); health = p.getHealth();
            generation = WorldManager.get().generation(id);
        }
    }
    public static SafeTeleportService get() { return INSTANCE; }
    public void clear() {
        searches.values().forEach(Search::releaseChunk);
        searches.clear(); cursor = 0;
    }
    public void cancel(UUID player) {
        Search search = searches.remove(player);
        if (search != null) search.releaseChunk();
    }
    public void cancelWorld(String id) {
        var iterator = searches.values().iterator();
        while (iterator.hasNext()) {
            Search search = iterator.next();
            if (search.destination.equals(id)) { search.releaseChunk(); iterator.remove(); }
        }
    }
    public void spawn(ServerPlayer player, String id) {
        GlobalPosInfo configured = WorldManager.get().spawn(id);
        if (configured != null) {
            queuePosition(player, configured, 0, "世界出生点", () -> true, p -> success(p, id));
            return;
        }
        start(player, id, false);
    }
    /** 定点传送与安全搜索共用队列；受理成功不等于传送完成。 */
    public boolean queuePosition(ServerPlayer player, GlobalPosInfo pos, double cost, String reason,
                                 BooleanSupplier valid, Consumer<ServerPlayer> onSuccess) {
        WorldManager manager = WorldManager.get();
        if (!validPosition(pos) || !Double.isFinite(cost) || cost < 0) {
            MessageUtil.sendError(player, "安全传送", "目标坐标或费用配置无效"); return false;
        }
        if (!manager.current(pos)) { MessageUtil.sendError(player, "安全传送", "该坐标属于旧一代资源世界，已失效"); return false; }
        ServerLevel level = pos.resolveLevel(player.server);
        if (level == null) { MessageUtil.sendError(player, "安全传送", "目标维度不存在或未加载"); return false; }
        if (!manager.guard(player, level)) return false;
        Search previous = searches.get(player.getUUID());
        if (previous != null) {
            // 冻结/监禁检查可能反复提交同一纠正点，不要每秒重启仍在准备的请求。
            if (manager.internal() && previous.fixed && previous.internal
                    && previous.destination.equals(pos.dimension) && previous.preferred.x == pos.x
                    && previous.preferred.y == pos.y && previous.preferred.z == pos.z
                    && previous.preferred.generation == pos.generation) return true;
            if (manager.internal()) cancel(player.getUUID());
            else { MessageUtil.sendError(player, "传送", "已有传送正在准备，请稍候或使用 /tpcancel"); return false; }
        }
        Search search = new Search(player, pos.dimension, false);
        search.fixed = true; search.internal = manager.internal(); search.preferred = pos.copy();
        search.cost = cost; search.reason = reason; search.valid = valid; search.onSuccess = onSuccess;
        searches.put(search.player, search);
        if (!search.internal) MessageUtil.sendInfo(player, "安全传送", "正在异步准备目标区块，请保持静止…");
        return true;
    }

    static boolean validPosition(GlobalPosInfo pos) {
        return pos != null && pos.dimension != null && !pos.dimension.isEmpty()
                && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z)
                && Float.isFinite(pos.yaw) && Float.isFinite(pos.pitch)
                && Math.abs(pos.x) <= 29999984 && Math.abs(pos.z) <= 29999984;
    }

    public void rtp(ServerPlayer player, String id) { start(player, id, true); }
    public void corresponding(ServerPlayer player, GlobalPosInfo preferred) {
        ServerLevel level = WorldManager.get().level(preferred.dimension);
        if (level == null || !level.getWorldBorder().isWithinBounds(new Vec3(preferred.x, preferred.y, preferred.z))) {
            WorldManager.get().rtp(player, preferred.dimension); return;
        }
        start(player, preferred.dimension, false, preferred);
    }
    private void start(ServerPlayer player, String id, boolean rtp) { start(player, id, rtp, null); }
    private void start(ServerPlayer player, String id, boolean rtp, GlobalPosInfo preferred) {
        if (searches.containsKey(player.getUUID())) { MessageUtil.sendError(player, "传送", "已有安全点搜索，请稍候或使用 /tpcancel"); return; }
        Search search = new Search(player, id, rtp); search.preferred = preferred;
        searches.put(player.getUUID(), search);
        MessageUtil.sendInfo(player, "安全传送", "正在分批寻找安全落点，请保持静止…");
    }
    public void tick(MinecraftServer server) {
        if (searches.isEmpty()) return;
        long now = System.nanoTime();
        var iterator = searches.values().iterator();
        while (iterator.hasNext()) {
            Search waiting = iterator.next();
            ServerPlayer online = server.getPlayerList().getPlayer(waiting.player);
            if (online == null || ++waiting.age > MAX_SEARCH_TICKS || now - waiting.startedNanos >= MAX_SEARCH_NANOS) {
                waiting.releaseChunk(); iterator.remove();
                if (online != null) MessageUtil.sendError(online, "安全传送", "搜索超时，请稍后重试");
            }
        }
        if (searches.isEmpty()) return;
        List<Search> jobs = new ArrayList<>(searches.values());
        Search search = jobs.get(Math.floorMod(cursor++, jobs.size()));
        ServerPlayer player = server.getPlayerList().getPlayer(search.player);
        if (player == null) { cancel(search.player); return; }
        WorldManager manager = WorldManager.get();
        WorldDefinition d = manager.definition(search.destination);
        String problem = !search.valid.getAsBoolean() ? "传送目标已失效，请重新发起传送"
                : search.internal ? null : manager.entryProblem(player, search.destination, true);
        if (problem == null && !search.internal && (!player.serverLevel().dimension().location().toString().equals(search.origin)
                || player.position().distanceToSqr(search.position) > 0.15 || player.getHealth() < search.health)) problem = "移动或受伤，搜索已取消";
        if (problem == null && search.generation != manager.generation(search.destination)) problem = "资源世界已重置，请重新发起传送";
        if (problem != null || d == null && !search.fixed) {
            cancel(search.player); MessageUtil.sendError(player, "安全传送", problem == null ? "目标世界已失效" : problem); return;
        }
        ServerLevel level = manager.level(search.destination);
        if (level == null) { cancel(search.player); MessageUtil.sendError(player, "安全传送", "目标世界已卸载"); return; }
        if (search.chunk == null) {
            if (searches.values().stream().filter(scheduled -> scheduled.chunk != null).count() >= MAX_CHUNK_PREPARATIONS) return;
            if (search.attempts++ >= (search.fixed ? 1 : d.rtpAttempts)) {
                cancel(search.player); MessageUtil.sendError(player, "安全传送", "在尝试上限内没有找到安全落点；未扣费，请重试或联系管理员设置出生点"); return;
            }
            int bound = search.fixed ? 1 : Math.max(1, Math.min(d.rtpMax, (int) (level.getWorldBorder().getSize() / 2) - 4));
            if (search.preferred != null) { search.x = search.preferred.toBlockPos().getX(); search.z = search.preferred.toBlockPos().getZ(); }
            else if (!search.rtp && search.attempts == 1) { search.x = 0; search.z = 0; }
            else {
                search.x = random.nextInt(bound * 2 + 1) - bound;
                search.z = random.nextInt(bound * 2 + 1) - bound;
                if (search.rtp && Math.max(Math.abs(search.x), Math.abs(search.z)) < Math.min(d.rtpMin, bound)) return;
            }
            try {
                search.chunk = TeleportChunkLoader.prepare(level, search.x >> 4, search.z >> 4);
            } catch (RuntimeException error) {
                io.wifi.habitrainsurvive.HabiTrainSurvive.LOGGER.warn("安全传送区块请求失败: {} [{}, {}]", search.destination, search.x >> 4, search.z >> 4, error);
                loadFailed(player, search);
            }
            return;
        }
        PendingChunkPreparation.State state = search.chunk.poll();
        if (state == PendingChunkPreparation.State.PENDING) return;
        if (state == PendingChunkPreparation.State.FAILED) {
            loadFailed(player, search);
            return;
        }
        if (search.fixed) {
            searches.remove(search.player);
            try {
                Runnable commit = () -> {
                    boolean moved = search.internal ? position(player, search.preferred)
                            : paidPosition(player, search.preferred, search.cost, search.reason);
                    if (moved) search.onSuccess.accept(player);
                };
                // 异步提交时重新建立内部纠正上下文，避免冻结/监禁的强制回退被普通限制挡住。
                if (search.internal) manager.recover(commit); else commit.run();
            } finally { search.releaseChunk(); }
            return;
        }
        GlobalPosInfo preferred = search.preferred;
        boolean exact = preferred != null && safe(level, preferred.toBlockPos())
                && level.getWorldBorder().isWithinBounds(player.getBoundingBox().move(preferred.x - player.getX(), preferred.y - player.getY(), preferred.z - player.getZ()))
                && level.noCollision(player, player.getBoundingBox().move(preferred.x - player.getX(), preferred.y - player.getY(), preferred.z - player.getZ()));
        BlockPos feet = exact ? preferred.toBlockPos() : safeColumn(level, search.x, search.z);
        if (feet == null) {
            search.releaseChunk();
            if (preferred != null) fallback(player, search);
            return;
        }
        double cost = search.rtp ? CmiManager.get().getRtpCost() : 0;
        double entry = player.serverLevel() == level || io.wifi.habitrainsurvive.permission.PermissionManager.get().hasPermission(player, "habi.world.admin") ? 0 : d.entryFee;
        if (!EconomyManager.get().hasBalance(player.getUUID(), cost + entry)) {
            cancel(search.player); MessageUtil.sendError(player, "安全传送", "余额不足，传送未执行"); return;
        }
        GlobalPosInfo pos = exact ? preferred : GlobalPosInfo.fromBlockPos(level, feet);
        searches.remove(search.player);
        try {
            // 区块票据保留至传送及成功结算结束，不能在验证前提前释放。
            if (!position(player, pos)) return;
            if (cost > 0) EconomyManager.get().withdraw(player.getUUID(), cost, "RTP " + d.id);
            if (search.rtp) CmiManager.get().getOrCreateProfile(player).lastRtpTimestamp = System.currentTimeMillis();
            else if (preferred == null) { manager.runtime(d.id).spawn = pos; manager.save(); }
            success(player, d.id);
        } finally {
            search.releaseChunk();
        }
    }
    private void loadFailed(ServerPlayer player, Search search) {
        search.releaseChunk();
        if (search.fixed) {
            cancel(search.player);
            MessageUtil.sendError(player, "安全传送", "目标区块准备失败，传送未执行且未扣费");
        } else if (search.preferred != null) fallback(player, search);
    }
    private void fallback(ServerPlayer player, Search search) {
        cancel(search.player);
        MessageUtil.sendInfo(player, "维度旅行", "对应坐标没有安全落点，改为安全随机传送");
        WorldManager.get().rtp(player, search.destination);
    }
    public static boolean paidPosition(ServerPlayer player, GlobalPosInfo pos, double cost, String reason) {
        WorldDefinition d = WorldManager.get().definition(pos.dimension);
        double entry = d == null || player.serverLevel().dimension().location().toString().equals(pos.dimension)
                || io.wifi.habitrainsurvive.permission.PermissionManager.get().hasPermission(player, "habi.world.admin") ? 0 : d.entryFee;
        if (!Double.isFinite(cost) || cost < 0 || !EconomyManager.get().hasBalance(player.getUUID(), cost + entry)) {
            MessageUtil.sendError(player, "传送", "余额不足，传送未执行"); return false;
        }
        if (!position(player, pos)) return false;
        if (cost > 0) EconomyManager.get().withdraw(player.getUUID(), cost, reason);
        return true;
    }

    public static boolean position(ServerPlayer player, GlobalPosInfo pos) {
        WorldManager manager = WorldManager.get();
        if (pos == null || !manager.current(pos)) { MessageUtil.sendError(player, "安全传送", "该坐标属于旧一代资源世界，已失效"); return false; }
        ServerLevel level = pos.resolveLevel(player.server);
        if (level == null) { MessageUtil.sendError(player, "安全传送", "目标维度不存在或未加载"); return false; }
        if (!validPosition(pos)) return false;
        if (!TeleportChunkLoader.ready(level, pos.toBlockPos().getX() >> 4, pos.toBlockPos().getZ() >> 4)) {
            MessageUtil.sendError(player, "安全传送", "目标区块尚未准备完成，请重新发起异步传送"); return false;
        }
        if (!manager.guard(player, level)) return false;
        BlockPos feet = pos.toBlockPos();
        var destinationBox = player.getBoundingBox().move(pos.x - player.getX(), pos.y - player.getY(), pos.z - player.getZ());
        if (!manager.internal() && (!safe(level, feet) || !level.getWorldBorder().isWithinBounds(destinationBox)
                || !level.noCollision(player, destinationBox))) {
            MessageUtil.sendError(player, "安全传送", "目标位置存在碰撞、危险方块、液体或没有安全支撑，请重新设置传送点"); return false;
        }
        boolean result = player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), pos.yaw, pos.pitch);
        if (result) { player.resetFallDistance(); player.setDeltaMovement(Vec3.ZERO); }
        return result;
    }
    public static BlockPos safeColumn(ServerLevel level, int x, int z) {
        if (level == null || level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return null;
        int max = Math.min(level.getMaxBuildHeight() - 3, level.dimensionType().ultraWarm() ? 120 : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1);
        int min = level.dimensionType().ultraWarm() ? Math.max(level.getMinBuildHeight() + 2, 30) : Math.max(level.getMinBuildHeight() + 2, max - 12);
        for (int y = max; y >= min; y--) {
            BlockPos pos = new BlockPos(x, y, z); if (safe(level, pos)) return pos;
        }
        return null;
    }
    public static boolean safe(ServerLevel level, BlockPos feet) {
        if (level == null || level.getChunkSource().getChunkNow(feet.getX() >> 4, feet.getZ() >> 4) == null) return false;
        if (feet.getY() < level.getMinBuildHeight() + 1 || feet.getY() + 2 >= level.getMaxBuildHeight() || !level.getWorldBorder().isWithinBounds(feet)) return false;
        BlockState below = level.getBlockState(feet.below()), at = level.getBlockState(feet), head = level.getBlockState(feet.above());
        return below.isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
                && below.getFluidState().isEmpty() && at.getFluidState().isEmpty() && head.getFluidState().isEmpty()
                && at.getCollisionShape(level, feet).isEmpty() && head.getCollisionShape(level, feet.above()).isEmpty()
                && !dangerous(below) && !dangerous(at) && !dangerous(head);
    }
    private static boolean dangerous(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL);
    }
    private static void success(ServerPlayer p, String id) { MessageUtil.sendSuccess(p, "世界导航", "已到达 " + WorldManager.get().displayName(id)); MessageUtil.playDing(p); }
}
