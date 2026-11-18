package io.wifi.habitrainsurvive.menu;

import io.wifi.habitrainsurvive.cmi.CmiManager;
import io.wifi.habitrainsurvive.cmi.PlayerProfileData;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.permission.PermissionManager;
import io.wifi.habitrainsurvive.town.TownManager;
import io.wifi.habitrainsurvive.world.WorldManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configurable navigation; existing business GUIs are entered using the player's own command source. */
public final class MenuManager {
    private static final MenuManager INSTANCE = new MenuManager();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    private final MenuConfig.Store store = new MenuConfig.Store();

    private MenuManager() { }

    public static MenuManager get() { return INSTANCE; }

    public void load(Path path) throws IOException { store.load(path, this::validateRegistries); }

    public void reload() throws IOException { store.reload(this::validateRegistries); }

    public MenuConfig snapshot() { return store.current(); }

    public void saveDraft(ServerPlayer player, MenuDraft draft) throws IOException {
        if (!player.hasPermissions(2)) throw new IllegalArgumentException("菜单编辑需要原版 OP 2 权限");
        MenuConfig parsed = MenuConfig.read(new java.io.StringReader(draft.json().toString()));
        com.google.gson.JsonObject authored = MenuConfig.toJson(parsed);
        boolean customLayout = parsed.menus().values().stream()
                .filter(page -> page.id().equals(parsed.defaultMenu()) || page.dynamic().equals("admin"))
                .anyMatch(page -> draft.base().menus().get(page.id()) == null
                        || !draft.base().menus().get(page.id()).buttons().equals(page.buttons()));
        // Editing a built-in command is also customization: do not recreate that command in another slot.
        if (customLayout) authored.addProperty("autoShortcuts", false);
        store.save(draft.base(), authored, candidate -> {
            if (!player.hasPermissions(2)) throw new IllegalArgumentException("菜单编辑权限已失效");
            if (!player.hasPermissions(4) && candidate.menus().values().stream().flatMap(p -> p.buttons().stream())
                    .flatMap(b -> b.actions().stream()).anyMatch(a -> a.type() == MenuAction.Type.CONSOLE_COMMAND))
                throw new IllegalArgumentException("含控制台指令的菜单配置必须由 OP 4 保存，防止菜单编辑权限提权");
            validateRegistries(candidate);
        });
    }

    public String expandFor(ServerPlayer player, String text) { return expand(text, placeholders(player)); }

    public void openSelector(ServerPlayer player) {
        MenuConfig config = store.current();
        if (config == null) { error(player, "菜单尚未加载。"); return; }
        List<MenuGui.Button> buttons = new ArrayList<>();
        menuIds(player).forEach(id -> {
            MenuDefinition page = config.menus().get(id);
            buttons.add(new MenuGui.Button(MenuGui.icon(Items.WRITABLE_BOOK, expandFor(player, page.title()),
                    "菜单 ID: " + id, "点击打开 · /menu open " + id), p -> open(p, id)));
        });
        if (player.hasPermissions(2)) buttons.add(new MenuGui.Button(MenuGui.icon(Items.COMMAND_BLOCK, "菜单编辑器"), MenuEditorGui::open));
        MenuGui.open(player, "菜单目录 · 选择菜单", buttons, 0, p -> open(p, null));
    }

    public boolean isSneakSwapEnabled() {
        MenuConfig config = store.current();
        return config != null && config.sneakSwapEnabled();
    }

    public List<String> menuIds(ServerPlayer player) {
        MenuConfig config = store.current();
        return config == null ? List.of() : config.menus().values().stream()
                .filter(page -> allowed(player, page.permission()) && (!page.dynamic().equals("admin") || allowed(player, "habi.admin")))
                .map(MenuDefinition::id).sorted().toList();
    }

    /** A null/empty id opens the configured default menu (also used by the shortcut mixin). */
    public void open(ServerPlayer player, String menuId) {
        MenuConfig config = store.current();
        if (config == null) { error(player, "菜单尚未加载。"); return; }
        String id = menuId == null || menuId.isEmpty() ? config.defaultMenu() : menuId;
        MenuDefinition page = config.menus().get(id);
        if (page == null) { error(player, "菜单不存在: " + id); return; }
        if (!canOpen(player, page)) return;
        if (!page.dynamic().isEmpty() && !page.dynamic().equals("admin")) {
            openDynamic(player, config, page);
            return;
        }
        Map<String, String> values = placeholders(player);
        Map<Integer, MenuGui.Button> buttons = new HashMap<>();
        for (MenuDefinition.Entry entry : page.buttons()) {
            if (entry.actions().stream().anyMatch(action -> {
                if (action.type() != MenuAction.Type.TELEPORT && action.type() != MenuAction.Type.RTP) return false;
                var target = WorldManager.get().definition(action.value());
                return io.wifi.habitrainsurvive.world.WorldConfig.excludedId(action.value())
                        || target != null && io.wifi.habitrainsurvive.world.WorldConfig.excluded(target);
            })) continue;
            boolean permitted = allowed(player, entry.permission()) && entry.actions().stream().allMatch(a -> allowed(player, a.permission()));
            List<String> lore = new ArrayList<>(entry.lore().stream().map(s -> expand(s, values)).toList());
            if (!permitted) lore.add("§c没有使用权限");
            ItemStack icon = MenuGui.icon(BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.icon())),
                    expand(entry.name(), values), lore.toArray(String[]::new));
            icon.setCount(entry.amount());
            buttons.put(entry.slot(), new MenuGui.Button(icon, guard(config, page, p -> {
                if (!require(p, entry.permission())) return;
                // Check every permission before executing even the first action in a sequence.
                for (MenuAction action : entry.actions()) if (!require(p, action.permission())) return;
                for (MenuAction action : entry.actions()) execute(p, page, action);
            })));
        }
        if (config.autoShortcuts() && page.id().equals(config.defaultMenu())) {
            addDimensionShortcut(buttons, config, page, "nether", "前往地狱", Items.OBSIDIAN, 37);
            addDimensionShortcut(buttons, config, page, "end", "前往末地", Items.ENDER_EYE, 38);
            addCommandShortcut(buttons, config, page, "playershop", "多人商铺", Items.ENDER_CHEST, 40);
            addCommandShortcut(buttons, config, page, "qs mine", "我的箱子商店", Items.BARREL, 42);
            addCommandShortcut(buttons, config, page, "plot", "地皮大厅", Items.GRASS_BLOCK, 43);
            addCommandShortcut(buttons, config, page, "qs browse", "箱子商店导购", Items.CHEST, 45);
            addCommandShortcut(buttons, config, page, "menus", "自定义菜单目录", Items.WRITABLE_BOOK, 44);
        }
        if (config.autoShortcuts() && page.dynamic().equals("admin")) {
            addCommandShortcut(buttons, config, page, "habi edit", "系统参数编辑", Items.WRITABLE_BOOK, 4);
            addCommandShortcut(buttons, config, page, "menu edit", "菜单编辑器", Items.ITEM_FRAME, 13);
        }
        MenuGui.openSlots(player, expand(page.title(), values), page.rows(), buttons);
    }

    private void addDimensionShortcut(Map<Integer, MenuGui.Button> buttons, MenuConfig config, MenuDefinition page,
            String command, String title, net.minecraft.world.item.Item icon, int preferred) {
        if (page.buttons().stream().flatMap(b -> b.actions().stream()).anyMatch(a -> a.type() == MenuAction.Type.PLAYER_COMMAND && a.value().equals(command))) return;
        int size = page.rows() * 9;
        for (int offset = 0; offset < size; offset++) {
            int slot = (preferred + offset) % size;
            if (buttons.containsKey(slot)) continue;
            buttons.put(slot, new MenuGui.Button(MenuGui.icon(icon, title,
                    "身边3格内有对应门方块：前往对应坐标", "否则寻找安全随机落点 · /" + command), guard(config, page, p -> {
                p.closeContainer(); playerCommand(p, command);
            })));
            return;
        }
    }

    private void addCommandShortcut(Map<Integer, MenuGui.Button> buttons, MenuConfig config, MenuDefinition page,
                                    String command, String title, net.minecraft.world.item.Item icon, int preferred) {
        if (page.buttons().stream().flatMap(b -> b.actions().stream()).anyMatch(a -> a.type() == MenuAction.Type.PLAYER_COMMAND && a.value().equals(command))) return;
        int size = page.rows() * 9;
        for (int offset = 0; offset < size; offset++) {
            int slot = (preferred + offset) % size;
            if (buttons.containsKey(slot)) continue;
            buttons.put(slot, new MenuGui.Button(MenuGui.icon(icon, title, "点击打开 · /" + command), guard(config, page, p -> {
                p.closeContainer(); playerCommand(p, command);
            })));
            return;
        }
    }

    private boolean canOpen(ServerPlayer player, MenuDefinition page) {
        return require(player, page.permission()) && (!page.dynamic().equals("admin") || require(player, "habi.admin"));
    }

    private Consumer<ServerPlayer> guard(MenuConfig snapshot, MenuDefinition page, Consumer<ServerPlayer> action) {
        return player -> {
            if (store.current() != snapshot) {
                error(player, "菜单配置已更新，已刷新页面，请重新选择。");
                open(player, store.current().menus().containsKey(page.id()) ? page.id() : null);
            } else if (canOpen(player, page)) {
                action.accept(player);
            }
        };
    }

    private void execute(ServerPlayer player, MenuDefinition page, MenuAction action) {
        switch (action.type()) {
            case OPEN -> open(player, action.value());
            case BACK -> { if (page.back().isEmpty()) player.closeContainer(); else open(player, page.back()); }
            case CLOSE -> player.closeContainer();
            case MESSAGE -> { if (!action.value().isEmpty()) player.sendSystemMessage(Component.literal(expand(action.value(), placeholders(player)))); }
            case SOUND -> player.playNotifySound(BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(action.value())),
                    SoundSource.MASTER, action.volume(), action.pitch());
            case TELEPORT -> { player.closeContainer(); WorldManager.get().teleport(player, action.value()); }
            case RTP -> { player.closeContainer(); WorldManager.get().rtp(player, action.value()); }
            case PLAYER_COMMAND, CONSOLE_COMMAND -> {
                String command;
                try { command = action.identityCommand(player.getGameProfile().getName(), player.getUUID()); }
                catch (IllegalArgumentException e) { error(player, e.getMessage()); return; }
                player.closeContainer();
                // No permission elevation, silent source or exception swallowing for player commands.
                if (action.type() == MenuAction.Type.PLAYER_COMMAND) playerCommand(player, command);
                else player.server.getCommands().performPrefixedCommand(player.server.createCommandSourceStack(), command);
            }
        }
    }

    private void openDynamic(ServerPlayer player, MenuConfig config, MenuDefinition page) {
        List<MenuGui.Button> buttons = new ArrayList<>();
        switch (page.dynamic()) {
            case "worlds" -> WorldManager.get().all().stream().sorted(Comparator.comparing(w -> w.id)).forEach(world -> {
                boolean loaded = WorldManager.get().isLoaded(world.id);
                boolean permitted = allowed(player, world.permission == null ? "" : world.permission);
                long online = player.server.getPlayerList().getPlayers().stream()
                        .filter(p -> p.serverLevel().dimension().location().toString().equals(world.id)).count();
                String state = !world.enabled ? "已停用" : !loaded ? "未加载（可能需要重启）" : !world.open ? "未开放" : "已开放";
                buttons.add(new MenuGui.Button(MenuGui.icon(loaded && world.enabled && world.open ? Items.GRASS_BLOCK : Items.BARRIER,
                        WorldManager.get().displayName(world.id), "世界: " + world.id,
                        "类型: " + world.kind + " / " + world.preset, "状态: " + state,
                        "在线: " + online + (world.playerLimit > 0 ? " / " + world.playerLimit : " / 不限"),
                        "进入费用: " + EconomyManager.get().format(world.entryFee),
                        permitted ? "点击前往（传送时重新验证）" : "§c缺少权限: " + world.permission),
                        guard(config, page, p -> {
                            p.closeContainer();
                            WorldManager.get().teleport(p, world.id);
                        })));
            });
            case "homes" -> {
                CmiManager cmi = CmiManager.get();
                int limit = cmi.getMaxHomesForPlayer(player.getUUID());
                if (limit < Integer.MAX_VALUE) buttons.add(new MenuGui.Button(MenuGui.icon(Items.EMERALD, "购买家上限 +1",
                        "当前家数量: " + cmi.getOrCreateProfile(player).homes.size() + " / " + limit,
                        "已购买额外额度: " + cmi.getPurchasedHomeSlots(player.getUUID()),
                        "本次费用: " + EconomyManager.get().format(cmi.nextHomeSlotPrice(player.getUUID())),
                        "价格100、500、900、1300……点击后再次确认"), guard(config, page, io.wifi.habitrainsurvive.cmi.HomeUpgradeGui::open)));
                cmi.getOrCreateProfile(player).homes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(home -> buttons.add(new MenuGui.Button(MenuGui.icon(Items.RED_BED, home.getKey(),
                                home.getValue().toShortString(), "点击回家 · /sethome 名称 设置新家"),
                                guard(config, page, p -> wordCommand(p, "home ", home.getKey())))));
            }
            case "warps" -> { io.wifi.habitrainsurvive.landmark.LandmarkGui.open(player); return; }
            case "towns" -> {
                if (!io.wifi.habitrainsurvive.config.ModuleConfig.isEnabled(io.wifi.habitrainsurvive.config.ModuleId.TOWN)) {
                    error(player, "城镇系统模块当前未启用。");
                    return;
                }
                var myTown = TownManager.get().getPlayerTown(player.getUUID());
                if (myTown != null) {
                    buttons.add(new MenuGui.Button(MenuGui.icon(Items.BELL, "打开我的城镇面板 [" + myTown.name + "]",
                            "镇长: " + myTown.mayorName, "居民: " + myTown.residents.size(),
                            "金库: " + EconomyManager.get().format(myTown.bankBalance), "点击进入城镇可视化管理面板"),
                            guard(config, page, io.wifi.habitrainsurvive.town.TownGui::open)));
                } else {
                    buttons.add(new MenuGui.Button(MenuGui.icon(Items.EMERALD_BLOCK, "创建或加入城镇",
                            "您尚未加入任何城镇", "点击打开城镇面板创建新城镇"),
                            guard(config, page, io.wifi.habitrainsurvive.town.TownGui::open)));
                }
                TownManager.get().getAllTowns().stream().sorted(Comparator.comparing(t -> t.name))
                        .forEach(town -> buttons.add(new MenuGui.Button(MenuGui.icon(Items.OAK_DOOR, town.name,
                                "镇长: " + town.mayorName, "居民: " + town.residents.size(),
                                "金库: " + EconomyManager.get().format(town.bankBalance), "公告: " + town.boardMessage,
                                "公共传送: " + (town.openToPublic ? "开放" : "仅本城及同盟"), "点击前往（身份与开放状态实时检查）"),
                                guard(config, page, p -> wordCommand(p, "town spawn ", town.name)))));
            }
            case "players" -> player.server.getPlayerList().getPlayers().stream()
                    .filter(target -> !target.getUUID().equals(player.getUUID()) && visible(player, target))
                    .sorted(Comparator.comparing(p -> p.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(target -> {
                        UUID uuid = target.getUUID();
                        buttons.add(new MenuGui.Button(MenuGui.icon(Items.PLAYER_HEAD, target.getGameProfile().getName(),
                                "点击发送安全交易请求", "仅使用玩家实体的真实账户名，不使用昵称"), guard(config, page, p -> {
                            ServerPlayer online = p.server.getPlayerList().getPlayer(uuid);
                            if (online == null || !visible(p, online)) { error(p, "该玩家已离线或不可见。"); return; }
                            String name;
                            try { name = MenuAction.safePlayerName(online.getGameProfile().getName()); }
                            catch (IllegalArgumentException e) { error(p, e.getMessage()); return; }
                            // Avoid collision with the trade command's literal accept/deny subcommands.
                            String argument = name.equals("accept") || name.equals("deny") ? uuid.toString() : name;
                            p.closeContainer();
                            playerCommand(p, "trade " + argument);
                        })));
                    });
            default -> throw new IllegalStateException("未识别的动态菜单: " + page.dynamic());
        }
        Consumer<ServerPlayer> back = page.back().isEmpty() ? null : p -> open(p, page.back());
        MenuGui.open(player, expand(page.title(), placeholders(player)), buttons, 0, back);
    }

    private static boolean visible(ServerPlayer viewer, ServerPlayer target) {
        return viewer.hasPermissions(2) || !CmiManager.get().getOrCreateProfile(target).vanished;
    }

    private static void wordCommand(ServerPlayer player, String prefix, String word) {
        String safe;
        try { safe = MenuAction.safeWord(word); }
        catch (IllegalArgumentException e) { error(player, "该名称不能通过命令传送: " + e.getMessage()); return; }
        player.closeContainer();
        playerCommand(player, prefix + safe);
    }

    private static void playerCommand(ServerPlayer player, String command) {
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static boolean allowed(ServerPlayer player, String permission) {
        return permission.isEmpty() || PermissionManager.get().hasPermission(player, permission);
    }

    private static boolean require(ServerPlayer player, String permission) {
        if (allowed(player, permission)) return true;
        error(player, "缺少权限: " + permission);
        return false;
    }

    private Map<String, String> placeholders(ServerPlayer player) {
        PlayerProfileData profile = CmiManager.get().getOrCreateProfile(player);
        var group = PermissionManager.get().getPlayerGroup(player.getUUID());
        long cooldown = Math.max(0, (CmiManager.get().getRtpCooldownForPlayer(player.getUUID()) * 1000
                - (System.currentTimeMillis() - profile.lastRtpTimestamp) + 999) / 1000);
        return Map.of("player", player.getGameProfile().getName(), "uuid", player.getUUID().toString(),
                "balance", EconomyManager.get().format(EconomyManager.get().getBalance(player.getUUID())),
                "world", WorldManager.get().displayName(player.serverLevel().dimension().location().toString()),
                "online", Integer.toString(player.server.getPlayerList().getPlayerCount()),
                "group", group == null ? "default" : group.name, "homes", Integer.toString(profile.homes.size()),
                "rtp_cooldown", Long.toString(cooldown), "worlds", Integer.toString(WorldManager.get().all().size()),
                "loaded_worlds", Long.toString(WorldManager.get().all().stream().filter(w -> WorldManager.get().isLoaded(w.id)).count()));
    }

    private static String expand(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), matcher.group())));
        matcher.appendTail(out);
        return out.toString();
    }

    private void validateRegistries(MenuConfig config) {
        for (MenuDefinition page : config.menus().values()) for (MenuDefinition.Entry entry : page.buttons()) {
            String at = "menus." + page.id() + ".buttons[slot=" + entry.slot() + "]";
            if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(entry.icon()))) {
                throw new IllegalArgumentException(at + ".icon: 物品不存在: " + entry.icon());
            }
            for (MenuAction action : entry.actions()) {
                if (action.type() == MenuAction.Type.SOUND && !BuiltInRegistries.SOUND_EVENT.containsKey(ResourceLocation.parse(action.value()))) {
                    throw new IllegalArgumentException(at + ".actions: 音效不存在: " + action.value());
                }
            }
        }
    }

    private static void error(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[菜单] " + text).withStyle(ChatFormatting.RED));
    }
}
