package io.wifi.habitrainsurvive.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import io.wifi.habitrainsurvive.hub.HubManager;
import io.wifi.habitrainsurvive.menu.MenuManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.security.Confirmations;
import io.wifi.habitrainsurvive.security.TeleportWarmupManager;
import io.wifi.habitrainsurvive.util.GlobalPosInfo;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class WorldCommands {
    private WorldCommands() {}
    private static WorldManager manager() { return WorldManager.get(); }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, net.minecraft.resources.ResourceLocation> worldArg() {
        return Commands.argument("world", ResourceLocationArgument.id()).suggests((ctx, b) -> SharedSuggestionProvider.suggest(manager().all().stream().map(w -> w.id), b));
    }
    private static String id(CommandContext<CommandSourceStack> ctx) {
        String id = ResourceLocationArgument.getId(ctx, "world").toString();
        WorldDefinition d = manager().definition(id);
        if (d == null && id.startsWith("minecraft:")) d = manager().definition(id.substring(10));
        return d == null ? id : d.id;
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nether").executes(ctx -> QuickDimensionTravel.go(ctx.getSource().getPlayerOrException(), false) ? 1 : 0));
        dispatcher.register(Commands.literal("end").executes(ctx -> QuickDimensionTravel.go(ctx.getSource().getPlayerOrException(), true) ? 1 : 0));
        for (String alias : List.of("world", "worlds")) dispatcher.register(Commands.literal(alias)
                .executes(ctx -> { if (ctx.getSource().getPlayer() != null) MenuManager.get().open(ctx.getSource().getPlayer(), "worlds"); else list(ctx.getSource()); return 1; })
                .then(worldArg().executes(ctx -> manager().teleport(ctx.getSource().getPlayerOrException(), id(ctx)) ? 1 : 0)));
        var root = Commands.literal("worldadmin").requires(s -> PermissionManager.get().hasPermission(s, "habi.world.admin", 2))
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("info").then(worldArg().executes(ctx -> info(ctx.getSource(), id(ctx)))))
                .then(Commands.literal("create").then(worldArg()
                        .then(Commands.argument("preset", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("normal", "nether", "end", "flat", "void"), b))
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .then(Commands.argument("kind", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("survival", "resource"), b))
                                                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(WorldCommands::create)))))))
                .then(Commands.literal("import").then(worldArg().then(Commands.argument("directory", StringArgumentType.greedyString()).executes(ctx -> change(ctx, "已登记地图导入，重启前请检查 manifest.json、种子和版本", d -> {
                    if (!d.managed() || manager().isLoaded(d.id)) throw new IllegalArgumentException("只能导入尚未加载的新建维度");
                    d.importSource = StringArgumentType.getString(ctx, "directory");
                })))))
                .then(Commands.literal("setspawn").then(worldArg().executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (!p.serverLevel().dimension().location().toString().equals(id(ctx)) || !SafeTeleportService.safe(p.serverLevel(), p.blockPosition())) return fail(ctx.getSource(), "请站在目标世界的安全位置");
                    return change(ctx, "世界出生点已设置", d -> d.spawn = GlobalPosInfo.fromPlayer(p));
                })))
                .then(Commands.literal("rule").then(worldArg().then(Commands.argument("rule", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("pvp", "build", "interact", "damage", "hunger", "mobs", "mobGrief", "explosions", "fireSpread", "fluids", "pistons", "allowClaims", "allowShops", "weather", "portals", "borderSize", "entryFee", "playerLimit", "rtpMin", "rtpMax", "resetIntervalHours", "fixedTime", "difficulty", "permission"), b))
                        .then(Commands.argument("value", StringArgumentType.word()).executes(ctx -> change(ctx, "世界规则已更新", d -> rule(d, StringArgumentType.getString(ctx, "rule"), StringArgumentType.getString(ctx, "value"))))))));
        for (String action : List.of("open", "close", "enable", "disable")) root.then(Commands.literal(action).then(worldArg().executes(ctx -> {
            String id = id(ctx);
            if (id.equals(WorldManager.HUB) && !action.equals("open") && !action.equals("enable")) return fail(ctx.getSource(), "默认主城不能关闭或停用");
            int result = change(ctx, action.equals("enable") || action.equals("disable") ? "启停登记已保存，重启生效" : "世界开放状态已更新", d -> {
                if (action.equals("enable")) d.enabled = true;
                else if (action.equals("disable")) { if (!d.managed()) throw new IllegalArgumentException("不能停用原版世界"); d.enabled = false; d.open = false; }
                else d.open = action.equals("open");
            });
            if (result == 1 && (action.equals("close") || action.equals("disable"))) {
                TeleportWarmupManager.get().cancelAll("目标世界已关闭"); SafeTeleportService.get().cancelWorld(id);
                if (manager().level(id) != null) for (ServerPlayer p : List.copyOf(manager().level(id).players())) HubManager.get().rescue(p);
            }
            return result;
        })));
        root.then(Commands.literal("reset").then(worldArg()
                .executes(ctx -> {
                    WorldDefinition d = manager().definition(id(ctx));
                    if (!ResourceResetManager.eligible(d)) return fail(ctx.getSource(), "只能重置本核心资源世界");
                    String token = Confirmations.issue(ctx.getSource(), "reset:" + d.id);
                    ctx.getSource().sendSuccess(() -> Component.literal("§c确认重置 " + d.name + "？将于下次启动备份旧地图；30秒内执行 /worldadmin reset " + d.id + " confirm " + token), false); return 1;
                }).then(Commands.literal("confirm").then(Commands.argument("token", StringArgumentType.word()).executes(ctx -> {
                    if (!Confirmations.consume(ctx.getSource(), "reset:" + id(ctx), StringArgumentType.getString(ctx, "token"))) return fail(ctx.getSource(), "确认无效或已过期");
                    try { manager().queueReset(id(ctx)); return 1; } catch (IOException e) { return fail(ctx.getSource(), e.getMessage()); }
                }))).then(Commands.literal("cancel").executes(ctx -> {
                    var r = manager().runtime(id(ctx));
                    if (r.resetBackup != null) return fail(ctx.getSource(), "已有文件事务，禁止取消；请恢复备份或完成启动");
                    r.pendingReset = false; manager().save(); return 1;
                }))));
        dispatcher.register(root);
    }
    private static int create(CommandContext<CommandSourceStack> ctx) {
        String id = id(ctx);
        if (manager().definition(id) != null || !id.startsWith("habi_train_survive:")) return fail(ctx.getSource(), "ID重复或不是本核心命名空间");
        try {
            if (Files.exists(WorldPaths.dimension(manager().saveRoot(), id))) return fail(ctx.getSource(), "目标地图目录已存在，拒绝覆盖");
            WorldDefinition d = new WorldDefinition(id, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "preset"), StringArgumentType.getString(ctx, "kind"));
            d.seed = LongArgumentType.getLong(ctx, "seed"); d.portals = false;
            if (d.resource()) { d.allowClaims = d.allowShops = false; d.resetIntervalHours = 720; }
            manager().update(c -> c.worlds.add(d));
            ctx.getSource().sendSuccess(() -> Component.literal("已登记 " + id + "，下次重启生成；可在重启前使用 /worldadmin import 导入同种子地图"), true); return 1;
        } catch (IOException | IllegalArgumentException e) { return fail(ctx.getSource(), e.getMessage()); }
    }
    private static int change(CommandContext<CommandSourceStack> ctx, String message, Consumer<WorldDefinition> change) {
        try {
            String id = id(ctx);
            manager().update(c -> {
                WorldDefinition d = c.worlds.stream().filter(w -> w.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("世界未登记"));
                change.accept(d);
            });
            ctx.getSource().sendSuccess(() -> Component.literal(message), true); return 1;
        } catch (IOException | IllegalArgumentException e) { return fail(ctx.getSource(), e.getMessage()); }
    }
    private static boolean bool(String value) {
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("布尔值必须为 true/false"); return Boolean.parseBoolean(value);
    }
    private static void rule(WorldDefinition d, String key, String v) {
        if (!d.managed() && Set.of("mobs", "mobGrief", "weather", "fixedTime", "difficulty", "borderSize").contains(key))
            throw new IllegalArgumentException("该独立规则只支持本核心维度；原版三世界请使用原版命令管理共享规则");
        switch (key) {
            case "pvp" -> d.pvp = bool(v); case "build" -> d.build = bool(v); case "interact" -> d.interact = bool(v);
            case "damage" -> d.damage = bool(v); case "hunger" -> d.hunger = bool(v); case "mobs" -> d.mobs = bool(v);
            case "mobGrief" -> d.mobGrief = bool(v); case "explosions" -> d.explosions = bool(v); case "fireSpread" -> d.fireSpread = bool(v);
            case "fluids" -> d.fluids = bool(v); case "pistons" -> d.pistons = bool(v); case "allowClaims" -> d.allowClaims = bool(v);
            case "allowShops" -> d.allowShops = bool(v); case "weather" -> d.weather = bool(v); case "portals" -> d.portals = bool(v);
            case "borderSize" -> d.borderSize = Double.parseDouble(v); case "entryFee" -> d.entryFee = Double.parseDouble(v);
            case "playerLimit" -> d.playerLimit = Integer.parseInt(v); case "rtpMin" -> d.rtpMin = Integer.parseInt(v); case "rtpMax" -> d.rtpMax = Integer.parseInt(v);
            case "resetIntervalHours" -> d.resetIntervalHours = Long.parseLong(v); case "fixedTime" -> d.fixedTime = Long.parseLong(v);
            case "difficulty" -> d.difficulty = v; case "permission" -> d.permission = v.equals("none") ? "" : v;
            default -> throw new IllegalArgumentException("未知规则: " + key);
        }
    }
    public static int list(CommandSourceStack source) {
        for (WorldDefinition d : manager().all()) info(source, d.id); return 1;
    }
    private static int info(CommandSourceStack source, String id) {
        WorldDefinition d = manager().definition(id); if (d == null) return fail(source, "世界不存在");
        var r = manager().runtime(id);
        source.sendSuccess(() -> Component.literal(d.name + " [" + d.id + "] " + (manager().isLoaded(id) ? "已加载" : "未加载/待重启")
                + " / " + (d.open ? "开放" : "关闭") + " / " + d.preset + " / 第" + r.generation + "代"
                + " / 进入费 " + d.entryFee + (r.pendingReset ? " / 等待备份重置" : "")
                + (manager().restartPending(d.id) ? " / 待重启应用登记变更" : "")
                + (d.resource() ? " / 上次重置 " + java.time.Instant.ofEpochMilli(r.lastReset) + " / 周期 " + d.resetIntervalHours + " 小时" : "")), false); return 1;
    }
    private static int fail(CommandSourceStack source, String message) { source.sendFailure(Component.literal(message)); return 0; }
}
