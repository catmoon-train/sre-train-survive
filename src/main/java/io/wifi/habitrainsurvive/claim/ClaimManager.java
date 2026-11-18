package io.wifi.habitrainsurvive.claim;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ClaimManager {
    private static final ClaimManager INSTANCE = new ClaimManager();

    public static class Storage {
        public double costPerBlock2D = 1.5;
        public int maxClaimsPerPlayer = 5;
        public Map<String, Claim> claims = new ConcurrentHashMap<>();
    }

    private Storage storage = new Storage();
    private final Map<UUID, BlockPos> pos1Selection = new ConcurrentHashMap<>();
    private final Map<UUID, BlockPos> pos2Selection = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectionDim = new ConcurrentHashMap<>();
    private final Map<UUID, String> currentPlayerClaimId = new ConcurrentHashMap<>();
    private final Map<UUID, GlobalPosInfo> lastSafePos = new ConcurrentHashMap<>();
    private final Map<UUID, Map.Entry<String, Integer>> activeVisualizations = new ConcurrentHashMap<>();

    public static ClaimManager get() {
        return INSTANCE;
    }

    public void load(Path path) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.claims == null) {
            this.storage.claims = new ConcurrentHashMap<>();
        }
    }

    public void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    public double getCostPerBlock2D() {
        return storage.costPerBlock2D;
    }

    public int getDefaultMaxClaims() { return storage.maxClaimsPerPlayer; }
    public void configure(double costPerBlock, int maxClaims) {
        if (!Double.isFinite(costPerBlock) || costPerBlock < 0 || costPerBlock > 1e9 || maxClaims < 1 || maxClaims > 100000)
            throw new IllegalArgumentException("圈地单价或数量上限无效");
        storage.costPerBlock2D = Math.round(costPerBlock * 100) / 100.0;
        storage.maxClaimsPerPlayer = maxClaims;
        ServerDataManager.saveAll();
    }

    public int getMaxClaimsForPlayer(UUID uuid) {
        int groupMax = PermissionManager.get().getPlayerGroup(uuid).maxClaims;
        return Math.max(storage.maxClaimsPerPlayer, groupMax);
    }

    public void giveClaimWand(ServerPlayer player) {
        ItemStack wand = new ItemStack(Items.WOODEN_HOE, 1);
        wand.set(DataComponents.CUSTOM_NAME, Component.literal("🪓 领地选区木锄 (左键点1 / 右键点2 / 潜行右键面板)").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        wand.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("• 左键点击方块: 设置领地对角点 #1").withStyle(ChatFormatting.YELLOW),
                Component.literal("• 右键点击方块: 设置领地对角点 #2").withStyle(ChatFormatting.YELLOW),
                Component.literal("• 潜行(Shift)+右键: 打开可视化领地控制面板 GUI").withStyle(ChatFormatting.AQUA)
        )));
        if (!player.getInventory().add(wand)) {
            player.drop(wand, false);
        }
        MessageUtil.sendSuccess(player, "领地工具", "已为您发放【领地选区木锄】！使用木锄左键/右键选点，潜行+右键可随时呼出领地控制面板。");
    }

    public void setPos1(ServerPlayer player, BlockPos pos) {
        String dim = player.serverLevel().dimension().location().toString();
        selectionDim.put(player.getUUID(), dim);
        pos1Selection.put(player.getUUID(), pos.immutable());
        notifySelectionProgress(player);
    }

    public void setPos2(ServerPlayer player, BlockPos pos) {
        String dim = player.serverLevel().dimension().location().toString();
        selectionDim.put(player.getUUID(), dim);
        pos2Selection.put(player.getUUID(), pos.immutable());
        notifySelectionProgress(player);
    }

    private void notifySelectionProgress(ServerPlayer player) {
        BlockPos p1 = pos1Selection.get(player.getUUID());
        BlockPos p2 = pos2Selection.get(player.getUUID());
        if (p1 != null && p2 != null) {
            long area = (long) (Math.abs(p1.getX() - p2.getX()) + 1) * (long) (Math.abs(p1.getZ() - p2.getZ()) + 1);
            double cost = area * storage.costPerBlock2D;
            player.sendSystemMessage(MessageUtil.prefix("领地选区", ChatFormatting.AQUA)
                    .append(Component.literal(String.format("当前选区面积: %d 平方格 | 预计费用: %s ", area, EconomyManager.get().format(cost))).withStyle(ChatFormatting.YELLOW))
                    .append(MessageUtil.commandButton("打开控制面板", "/claim gui", "点击打开领地控制中心", ChatFormatting.GREEN)));
        }
    }

    public BlockPos getPos1(UUID uuid) {
        return pos1Selection.get(uuid);
    }

    public BlockPos getPos2(UUID uuid) {
        return pos2Selection.get(uuid);
    }

    public String getSelectionDim(UUID uuid) {
        return selectionDim.get(uuid);
    }

    /**
     * 优先匹配子领地 (Sub-Claim)，若不在子领地内则匹配父领地。
     */
    public Claim getClaimAt(String dimension, BlockPos pos) {
        if (io.wifi.habitrainsurvive.plot.PlotGrid.WORLD.equals(dimension))
            return io.wifi.habitrainsurvive.plot.PlotManager.get().claimAt(dimension, pos);
        Claim matchedParent = null;
        for (Claim claim : storage.claims.values()) {
            if (claim.contains(dimension, pos.getX(), pos.getY(), pos.getZ())) {
                if (claim.isSubClaim()) {
                    return claim;
                }
                matchedParent = claim;
            }
        }
        return matchedParent;
    }

    public Claim getClaimByName(String name) {
        return storage.claims.get(name.toLowerCase(Locale.ROOT));
    }

    public List<Claim> getPlayerClaims(UUID owner) {
        return storage.claims.values().stream()
                .filter(c -> c.ownerUuid.equals(owner))
                .collect(Collectors.toList());
    }

    public List<Claim> getAccessibleClaims(UUID playerUuid) {
        return storage.claims.values().stream()
                .filter(c -> c.ownerUuid.equals(playerUuid) || c.isTrustedMember(playerUuid))
                .sorted(Comparator.comparing((Claim c) -> !c.ownerUuid.equals(playerUuid)).thenComparing(c -> c.name))
                .collect(Collectors.toList());
    }

    public Collection<Claim> getAllClaims() {
        return storage.claims.values();
    }

    public boolean hasIntersection(String dim, BlockPos p1, BlockPos p2, String ignoreClaimId) {
        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX());
        int maxY = Math.max(p1.getY(), p2.getY());
        int maxZ = Math.max(p1.getZ(), p2.getZ());
        for (Claim existing : storage.claims.values()) {
            if (existing.isSubClaim()) continue;
            if (ignoreClaimId != null && existing.id.equalsIgnoreCase(ignoreClaimId)) continue;
            if (existing.intersects(dim, minX, minY, minZ, maxX, maxY, maxZ)) {
                return true;
            }
        }
        return false;
    }

    public void addClaim(Claim claim) {
        storage.claims.put(claim.id.toLowerCase(Locale.ROOT), claim);
    }

    public Claim removeClaim(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        // 同时移除其下属的子领地
        storage.claims.values().removeIf(c -> key.equalsIgnoreCase(c.parentClaimId));
        return storage.claims.remove(key);
    }

    public void startVisualization(ServerPlayer player, Claim claim, int seconds) {
        activeVisualizations.put(player.getUUID(), new AbstractMap.SimpleEntry<>(claim.id, seconds));
    }

    private void renderBorderParticles(ServerPlayer player, int minX, int minZ, int maxX, int maxZ) {
        double y = player.getY() + 0.5;
        int step = Math.max(1, Math.max(maxX - minX, maxZ - minZ) / 32);
        for (int x = minX; x <= maxX; x += step) {
            player.serverLevel().sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, x + 0.5, y, minZ + 0.5, 1, 0, 0.3, 0, 0);
            player.serverLevel().sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, x + 0.5, y, maxZ + 0.5, 1, 0, 0.3, 0, 0);
        }
        for (int z = minZ; z <= maxZ; z += step) {
            player.serverLevel().sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, minX + 0.5, y, z + 0.5, 1, 0, 0.3, 0, 0);
            player.serverLevel().sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, maxX + 0.5, y, z + 0.5, 1, 0, 0.3, 0, 0);
        }
    }

    public void tickPlayerRegions(MinecraftServer server) {
        // 1. 渲染激活的领地边界粒子
        Iterator<Map.Entry<UUID, Map.Entry<String, Integer>>> vit = activeVisualizations.entrySet().iterator();
        while (vit.hasNext()) {
            Map.Entry<UUID, Map.Entry<String, Integer>> entry = vit.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            Claim c = getClaimByName(entry.getValue().getKey());
            int left = entry.getValue().getValue() - 1;
            if (p == null || c == null || left <= 0) {
                vit.remove();
            } else {
                renderBorderParticles(p, c.minX, c.minZ, c.maxX, c.maxZ);
                entry.setValue(new AbstractMap.SimpleEntry<>(c.id, left));
            }
        }

        // 2. 玩家手持木锄时实时预览选区边框粒子，并处理进出领地检测与领地飞行
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getMainHandItem().is(Items.WOODEN_HOE)) {
                BlockPos p1 = pos1Selection.get(player.getUUID());
                BlockPos p2 = pos2Selection.get(player.getUUID());
                if (p1 != null && p2 != null) {
                    renderBorderParticles(player, Math.min(p1.getX(), p2.getX()), Math.min(p1.getZ(), p2.getZ()),
                            Math.max(p1.getX(), p2.getX()), Math.max(p1.getZ(), p2.getZ()));
                }
            }

            String dim = player.serverLevel().dimension().location().toString();
            Claim current = getClaimAt(dim, player.blockPosition());
            String prevId = currentPlayerClaimId.getOrDefault(player.getUUID(), "");
            String curId = current != null ? current.id : "";

            if (current != null && !current.canEnter(player.getUUID()) && !player.hasPermissions(2)) {
                GlobalPosInfo safe = lastSafePos.get(player.getUUID());
                if (safe != null) {
                    io.wifi.habitrainsurvive.world.WorldManager.get().recover(() -> safe.teleportAsync(player));
                } else {
                    io.wifi.habitrainsurvive.world.WorldManager.get().recover(() -> player.teleportTo(player.getX() - 2, player.getY(), player.getZ() - 2));
                }
                MessageUtil.sendError(player, "领地门禁", "您没有权限踏入领地 [" + current.name + "]！");
                continue;
            } else {
                lastSafePos.put(player.getUUID(), GlobalPosInfo.fromPlayer(player));
            }

            if (!Objects.equals(prevId, curId)) {
                if (!prevId.isEmpty()) {
                    Claim oldClaim = storage.claims.get(prevId.toLowerCase(Locale.ROOT));
                    if (oldClaim != null && oldClaim.leaveMessage != null && !oldClaim.leaveMessage.isEmpty()) {
                        player.displayClientMessage(Component.literal(oldClaim.leaveMessage).withStyle(ChatFormatting.GRAY), true);
                    }
                    // 离开领地时若非创造/观察模式且无全局飞行权限，自动关闭领地飞行
                    if (!player.isCreative() && !player.isSpectator() && !player.hasPermissions(2)) {
                        player.getAbilities().mayfly = false;
                        player.getAbilities().flying = false;
                        player.onUpdateAbilities();
                    }
                }
                if (!curId.isEmpty() && current != null) {
                    if (current.enterMessage != null && !current.enterMessage.isEmpty()) {
                        player.displayClientMessage(Component.literal(current.enterMessage).withStyle(ChatFormatting.AQUA), true);
                    }
                    if (current.canFly(player.getUUID())) {
                        player.getAbilities().mayfly = true;
                        player.onUpdateAbilities();
                    }
                }
                currentPlayerClaimId.put(player.getUUID(), curId);
            }
        }
    }
}
