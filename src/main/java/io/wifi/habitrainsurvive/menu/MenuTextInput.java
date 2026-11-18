package io.wifi.habitrainsurvive.menu;

import io.wifi.habitrainsurvive.util.MessageUtil;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Optional long-text input for menu authors; intercepted messages never enter public chat. */
public final class MenuTextInput {
    private static final Map<UUID, Request> PENDING = new ConcurrentHashMap<>();
    private record Request(String initial, int max, StringBuilder buffer, long deadline,
                           BooleanSupplier valid, Consumer<String> accepted, Runnable back) { }
    private MenuTextInput() { }

    public static void registerEvents() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, player, parameters) -> {
            Request request = PENDING.get(player.getUUID());
            if (request == null) return true;
            String text = message.signedContent();
            player.server.execute(() -> receive(player, request, text));
            return false;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.getPlayer().getUUID()));
    }
    public static void request(ServerPlayer player, String initial, int max, BooleanSupplier valid,
                               Consumer<String> accepted, Runnable back) {
        if (!valid.getAsBoolean()) return;
        PENDING.put(player.getUUID(), new Request(initial, max, new StringBuilder(), System.nanoTime() + 300_000_000_000L, valid, accepted, back));
        player.closeContainer();
        MessageUtil.sendInfo(player, "菜单输入", "录入指令请以!起头，例如 !/warp spawn；不要直接发送/开头的真实指令。");
        MessageUtil.sendInfo(player, "菜单输入", "输入内容仅用于菜单，不会公开。可分多条连续追加（不自动插空格），上限" + max + "字。");
        MessageUtil.sendInfo(player, "菜单输入", "发送【完成】确认、【清空】重输、【取消】返回；/menu cancel 也可取消。以!开头可输入这些保留词。5分钟有效。");
    }
    private static void receive(ServerPlayer player, Request request, String text) {
        if (PENDING.get(player.getUUID()) != request) return;
        if (System.nanoTime() > request.deadline || !request.valid.getAsBoolean()) {
            PENDING.remove(player.getUUID(), request);
            MessageUtil.sendError(player, "菜单输入", "输入超时或权限/草稿已失效，请重新进入编辑器。"); return;
        }
        if (text.equals("取消")) { cancel(player, true); return; }
        if (text.equals("清空")) { request.buffer.setLength(0); MessageUtil.sendInfo(player, "菜单输入", "缓冲区已清空。"); return; }
        if (text.equals("完成")) {
            String value = request.buffer.isEmpty() ? request.initial : request.buffer.toString();
            try {
                request.accepted.accept(value);
                PENDING.remove(player.getUUID(), request);
            } catch (IllegalArgumentException e) { MessageUtil.sendError(player, "菜单输入", e.getMessage() + "；可清空重输或取消。"); }
            return;
        }
        if (text.startsWith("!")) text = text.substring(1);
        if (text.chars().anyMatch(Character::isISOControl) || request.buffer.length() + text.length() > request.max) {
            MessageUtil.sendError(player, "菜单输入", "内容含控制字符或超过" + request.max + "字，未追加。"); return;
        }
        request.buffer.append(text);
        MessageUtil.sendInfo(player, "菜单输入", "已接收" + request.buffer.length() + "字；发送【完成】保存到草稿。");
    }
    public static void cancel(ServerPlayer player, boolean returnToEditor) {
        Request request = PENDING.remove(player.getUUID());
        if (request != null && returnToEditor && request.valid.getAsBoolean()) request.back.run();
    }
    public static void clear() { PENDING.clear(); }
}
