package io.wifi.habitrainsurvive.plot;

import io.wifi.habitrainsurvive.claim.Claim;
import io.wifi.habitrainsurvive.claim.ClaimFlag;
import io.wifi.habitrainsurvive.config.CoreConfigManager;
import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import io.wifi.habitrainsurvive.util.MessageUtil;
import io.wifi.habitrainsurvive.world.WorldManager;
import io.wifi.habitrainsurvive.world.SafeTeleportService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Plot claims live in one store, but are exposed to the existing claim protection layer. */
public final class PlotManager {
    private static final PlotManager INSTANCE = new PlotManager();
    public static final class Storage {
        public PlotRules rules = new PlotRules();
        public Map<String, Claim> plots = new LinkedHashMap<>();
        public Set<UUID> freeUsed = new HashSet<>();
    }
    private Storage storage = new Storage();
    private Path file;
    private boolean ready;
    private PlotManager() { }
    public static PlotManager get() { return INSTANCE; }

    public void load(Path path) throws IOException {
        ready = false;
        storage = CoreConfigManager.load(path, Storage.class, new Storage(), this::validate);
        file = path; ready = true;
    }
    private void validate(Storage data) {
        if (data.rules == null || data.plots == null || data.freeUsed == null) throw new IllegalArgumentException("地皮数据缺少规则或所有权记录");
        data.rules.validate();
        for (var entry : data.plots.entrySet()) {
            Claim claim = entry.getValue();
            if (claim == null || claim.ownerUuid == null || claim.name == null || claim.flags == null || claim.members == null || claim.memberNames == null || claim.teleportPos == null)
                throw new IllegalArgumentException("无效地皮: " + entry.getKey());
            PlotGrid.Cell cell = PlotGrid.at(claim.minX, claim.minZ);
            if (cell == null || !cell.id().equals(entry.getKey()) || !PlotGrid.WORLD.equals(claim.dimension)
                    || claim.minX != cell.minX() || claim.maxX != cell.maxX() || claim.minZ != cell.minZ() || claim.maxZ != cell.maxZ()
                    || claim.minY != -64 || claim.maxY != 319 || !id(cell).equals(claim.id))
                throw new IllegalArgumentException("地皮边界或ID与网格不一致: " + entry.getKey());
            if (!PlotGrid.WORLD.equals(claim.teleportPos.dimension) || !Double.isFinite(claim.teleportPos.x)
                    || !Double.isFinite(claim.teleportPos.y) || !Double.isFinite(claim.teleportPos.z))
                throw new IllegalArgumentException("无效地皮传送点: " + entry.getKey());
            data.freeUsed.add(claim.ownerUuid);
        }
    }
    public void save() throws IOException { if (ready && file != null) CoreConfigManager.write(file, storage); }
    public void stopped() { ready = false; storage = new Storage(); file = null; }
    public PlotRules rules() {
        PlotRules copy = new PlotRules(); copy.additionalPlotPrice = storage.rules.additionalPlotPrice;
        copy.maxPlotsPerPlayer = storage.rules.maxPlotsPerPlayer; return copy;
    }
    public List<Claim> all() { return List.copyOf(storage.plots.values()); }
    public List<Claim> owned(UUID owner) { return all().stream().filter(c -> c.ownerUuid.equals(owner)).toList(); }
    public List<Claim> accessible(UUID player) { return all().stream().filter(c -> c.ownerUuid.equals(player) || c.isTrustedMember(player)).toList(); }
    public double price(UUID owner) { return storage.rules.price(owner, storage.freeUsed); }
    public Claim get(PlotGrid.Cell cell) { return cell == null ? null : storage.plots.get(cell.id()); }
    public Claim claimAt(String dimension, BlockPos pos) { return PlotGrid.WORLD.equals(dimension) ? get(PlotGrid.at(pos.getX(), pos.getZ())) : null; }
    public Claim byId(String id) { return all().stream().filter(c -> c.id.equals(id)).findFirst().orElse(null); }
    public boolean exists(Claim claim) { return claim != null && byId(claim.id) == claim; }
    public boolean isPlot(Claim claim) { return claim != null && PlotGrid.WORLD.equals(claim.dimension) && exists(claim); }
    private static String id(PlotGrid.Cell cell) { return "plot_" + cell.x() + "_" + cell.z(); }

    public PlotGrid.Cell nextAvailable() {
        var world = WorldManager.get().definition(PlotGrid.WORLD);
        if (!ready || world == null) return null;
        // The first vacant cell must occur before the occupied count, except for cells beyond a shrunken border.
        for (long i = 0; i <= 1_000_000; i++) {
            PlotGrid.Cell cell = PlotGrid.candidate(i);
            if (Math.max(Math.abs(cell.x()), Math.abs(cell.z())) * (long) PlotGrid.STRIDE > world.borderSize / 2 + PlotGrid.STRIDE) return null;
            if (cell.withinBorder(world.borderSize) && get(cell) == null) return cell;
        }
        return null;
    }

    public synchronized Claim purchase(ServerPlayer player, PlotGrid.Cell cell, double quotedPrice) {
        if (!ready) throw new IllegalArgumentException("地皮系统尚未加载");
        var world = WorldManager.get().definition(PlotGrid.WORLD);
        if (world == null || !"flat".equals(world.preset) || !WorldManager.get().isLoaded(PlotGrid.WORLD))
            throw new IllegalArgumentException("地皮超平坦世界尚未加载；首次安装后需要完整重启");
        String problem = WorldManager.get().entryProblem(player, PlotGrid.WORLD, true);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (cell == null || !cell.withinBorder(world.borderSize) || get(cell) != null) throw new IllegalArgumentException("这块地皮已售出或超出世界边界，请刷新");
        double cost = price(player.getUUID());
        if (Double.compare(cost, quotedPrice) != 0) throw new IllegalArgumentException("购地价格已更新，请重新确认");
        if (storage.rules.maxPlotsPerPlayer > 0 && owned(player.getUUID()).size() >= storage.rules.maxPlotsPerPlayer)
            throw new IllegalArgumentException("已达到个人地皮数量上限");
        if (cost > 0 && !EconomyManager.get().withdraw(player.getUUID(), cost, "Plot purchase " + cell.id())) throw new IllegalArgumentException("金币不足");
        Claim claim = new Claim(id(cell), "地皮 " + cell.id(), player.getUUID(), player.getGameProfile().getName(), PlotGrid.WORLD,
                new BlockPos(cell.minX(), -64, cell.minZ()), new BlockPos(cell.maxX(), 319, cell.maxZ()),
                new GlobalPosInfo(PlotGrid.WORLD, cell.minX() + 0.5, PlotGrid.FEET, cell.minZ() - 1.5, 0, 0));
        claim.flags.put(ClaimFlag.PVP, false);
        claim.flags.put(ClaimFlag.EXPLOSION, false);
        claim.flags.put(ClaimFlag.FIRE_SPREAD, false);
        boolean alreadyFree = storage.freeUsed.contains(player.getUUID());
        storage.plots.put(cell.id(), claim); storage.freeUsed.add(player.getUUID());
        try { save(); }
        catch (IOException e) {
            storage.plots.remove(cell.id());
            if (!alreadyFree) storage.freeUsed.remove(player.getUUID());
            if (cost > 0) EconomyManager.get().deposit(player.getUUID(), cost, "Plot save rollback");
            throw new IllegalArgumentException("保存地皮失败，已撤销购地: " + e.getMessage());
        }
        ServerDataManager.saveAll();
        MessageUtil.sendSuccess(player, "地皮", "已购得 " + claim.name + "（64×64），费用 " + EconomyManager.get().format(cost));
        return claim;
    }

    public void update(Claim target, Consumer<Claim> change) {
        if (!exists(target)) throw new IllegalArgumentException("地皮已变更，请刷新");
        Claim next = CoreConfigManager.GSON.fromJson(CoreConfigManager.GSON.toJson(target), Claim.class);
        change.accept(next);
        PlotGrid.Cell cell = PlotGrid.at(target.minX, target.minZ);
        storage.plots.put(cell.id(), next);
        try { validate(storage); save(); }
        catch (IOException | IllegalArgumentException e) {
            storage.plots.put(cell.id(), target);
            throw new IllegalArgumentException("地皮修改未保存: " + e.getMessage());
        }
    }
    public void updateRules(Consumer<PlotRules> change) {
        PlotRules next = rules(); change.accept(next); next.validate();
        PlotRules before = storage.rules; storage.rules = next;
        try { save(); }
        catch (IOException e) { storage.rules = before; throw new IllegalArgumentException("保存失败: " + e.getMessage()); }
    }
    public void teleport(ServerPlayer player, Claim claim) {
        if (!exists(claim) || !(claim.canEnter(player.getUUID()) && claim.getFlag(ClaimFlag.TELEPORT) || claim.ownerUuid.equals(player.getUUID()) || player.hasPermissions(2))) {
            MessageUtil.sendError(player, "地皮", "该地皮不允许访问或已失效"); return;
        }
        GlobalPosInfo target = claim.teleportPos.copy();
        player.closeContainer();
        TeleportWarmupManager.get().scheduleTeleport(player, claim.name, () -> SafeTeleportService.get().queuePosition(player, target, 0, "地皮传送",
                () -> exists(claim) && (claim.ownerUuid.equals(player.getUUID()) || player.hasPermissions(2) || claim.canEnter(player.getUUID()) && claim.getFlag(ClaimFlag.TELEPORT)), p -> { }));
    }
}
