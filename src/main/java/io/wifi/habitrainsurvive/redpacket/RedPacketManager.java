package io.wifi.habitrainsurvive.redpacket;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import io.wifi.habitrainsurvive.economy.EconomyManager;
import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class RedPacketManager {
    public static final long TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_ACTIVE_PER_SENDER = 5;
    private static final int MAX_HISTORY = 200;
    private static final RedPacketManager INSTANCE = new RedPacketManager();

    public static class Storage {
        public Map<String, RedPacket> packets = new LinkedHashMap<>();
    }

    private Storage storage = new Storage();
    private Path file;
    private final RandomGenerator random = new java.util.Random();

    private RedPacketManager() {}
    public static RedPacketManager get() { return INSTANCE; }

    public synchronized void load(Path path) {
        this.storage = ServerDataManager.readJson(path, Storage.class, new Storage());
        if (this.storage.packets == null) this.storage.packets = new LinkedHashMap<>();
        this.storage.packets.entrySet().removeIf(entry -> entry.getValue() == null || !entry.getValue().normalize());
        pruneHistory(System.currentTimeMillis());
        this.file = path;
    }

    public synchronized void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    public synchronized void save() {
        if (file != null) save(file);
    }

    private void pruneHistory(long now) {
        List<Map.Entry<String, RedPacket>> sorted = new ArrayList<>(storage.packets.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<String, RedPacket> e) -> e.getValue().createdAt).reversed());
        for (int i = MAX_HISTORY; i < sorted.size(); i++) storage.packets.remove(sorted.get(i).getKey());
    }

    public synchronized RedPacket get(String id) {
        return id == null ? null : storage.packets.get(id);
    }

    /** Open packets, newest first. */
    public synchronized List<RedPacket> active(long now) {
        List<RedPacket> list = new ArrayList<>();
        for (RedPacket packet : storage.packets.values()) if (packet.open(now)) list.add(packet);
        list.sort(Comparator.comparingLong((RedPacket packet) -> packet.createdAt).reversed());
        return list;
    }

    public synchronized boolean send(ServerPlayer sender, double amount, int count, String message) {
        long cents;
        try {
            cents = RedPacketValues.cents(amount);
            RedPacketValues.count(count);
        } catch (IllegalArgumentException invalid) {
            MessageUtil.sendError(sender, "拼手气红包", invalid.getMessage());
            return false;
        }
        if (cents < count) {
            MessageUtil.sendError(sender, "拼手气红包", "总额不足以让每份至少 0.01 金币");
            return false;
        }
        long now = System.currentTimeMillis();
        long activeMine = storage.packets.values().stream()
                .filter(packet -> packet.open(now) && sender.getUUID().equals(packet.senderId())).count();
        if (activeMine >= MAX_ACTIVE_PER_SENDER) {
            MessageUtil.sendError(sender, "拼手气红包", "你最多同时存在 " + MAX_ACTIVE_PER_SENDER + " 个未抢完的红包");
            return false;
        }
        String id = newId();
        if (!EconomyManager.get().withdraw(sender.getUUID(), cents / 100.0, "Red packet " + id)) {
            MessageUtil.sendError(sender, "拼手气红包", "余额不足，扣除失败：" + EconomyManager.get().format(cents / 100.0));
            MessageUtil.playError(sender);
            return false;
        }
        RedPacket packet = new RedPacket(id, sender.getUUID(), sender.getGameProfile().getName(), cents, count,
                now, now + TTL_MILLIS, RedPacketValues.message(message));
        storage.packets.put(id, packet);
        save();
        pruneHistory(now);

        MessageUtil.sendSuccess(sender, "拼手气红包",
                "已发出 " + EconomyManager.get().format(cents / 100.0) + " 的 " + count + " 份拼手气红包，5 分钟内可抢");
        sender.server.getPlayerList().broadcastSystemMessage(MessageUtil.prefix("拼手气红包", ChatFormatting.GOLD)
                .append(Component.literal(packet.senderName + " 发了 " + EconomyManager.get().format(cents / 100.0)
                        + " / " + count + " 份红包！留言: " + packet.message + " ").withStyle(ChatFormatting.YELLOW))
                .append(MessageUtil.commandButton("点击抢红包", "/redpacket claim " + id,
                        "先到先得，每人一份，5 分钟后未抢完的部分退回", ChatFormatting.GREEN)), false);
        return true;
    }

    public synchronized boolean claim(ServerPlayer player, String id) {
        RedPacket packet = get(id);
        if (packet == null) {
            MessageUtil.sendError(player, "拼手气红包", "红包不存在或已被清理");
            return false;
        }
        long now = System.currentTimeMillis();
        if (packet.claimedBy(player.getUUID())) {
            MessageUtil.sendError(player, "拼手气红包", "你已经抢过这个红包了");
            return false;
        }
        if (packet.expired(now)) {
            if (packet.remainingCents > 0) closeAndRefund(packet, "红包过期退回");
            MessageUtil.sendError(player, "拼手气红包", "这个红包已经过期，剩余部分已退回发送者");
            return false;
        }
        if (!packet.open(now)) {
            MessageUtil.sendError(player, "拼手气红包", "来晚一步，红包已经被抢完了");
            return false;
        }
        long share = packet.claim(player.getUUID(), now, random);
        if (share <= 0) {
            MessageUtil.sendError(player, "拼手气红包", "这个红包暂时无法领取");
            return false;
        }
        // Persist the claim before publishing money so a crash cannot pay the same share twice.
        save();
        EconomyManager.get().deposit(player.getUUID(), share / 100.0, "Red packet claim " + packet.id);
        MessageUtil.sendSuccess(player, "拼手气红包", "抢到 " + EconomyManager.get().format(share / 100.0)
                + "！剩余 " + packet.remainingCount + " 份");
        MessageUtil.playDing(player);
        if (packet.remainingCount == 0) {
            ServerPlayer sender = online(player.server, packet.senderId());
            if (sender != null) MessageUtil.sendInfo(sender, "拼手气红包", "你的红包已被抢完！");
            player.server.getPlayerList().broadcastSystemMessage(MessageUtil.prefix("拼手气红包", ChatFormatting.GOLD)
                    .append(Component.literal("发送者 " + packet.senderName + " 的 " + packet.count + " 份红包已被抢完！")
                            .withStyle(ChatFormatting.YELLOW)), false);
        }
        return true;
    }

    public synchronized boolean cancel(ServerPlayer player, String id) {
        RedPacket packet = get(id);
        if (packet == null) {
            MessageUtil.sendError(player, "拼手气红包", "红包不存在");
            return false;
        }
        UUID sender = packet.senderId();
        if (!player.getUUID().equals(sender) && !player.hasPermissions(2)) {
            MessageUtil.sendError(player, "拼手气红包", "只有发送者或管理员可以撤回红包");
            return false;
        }
        if (!packet.open(System.currentTimeMillis())) {
            MessageUtil.sendError(player, "拼手气红包", "红包已经结束，无需撤回");
            return false;
        }
        long refund = packet.remainingCents;
        closeAndRefund(packet, "Red packet cancel " + packet.id);
        MessageUtil.sendSuccess(player, "拼手气红包", "已撤回红包，剩余 " + EconomyManager.get().format(refund / 100.0) + " 已退回发送者");
        return true;
    }

    /** Expires overdue packets and refunds whatever nobody claimed. Called once per second. */
    public synchronized void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (RedPacket packet : new ArrayList<>(storage.packets.values())) {
            if (packet.expired(now) && packet.remainingCents > 0) closeAndRefund(packet, "Red packet expired");
        }
    }

    private void closeAndRefund(RedPacket packet, String reason) {
        long refund = packet.remainingCents;
        packet.close();
        // Persist the closed state first: a crash must not allow a second refund.
        save();
        UUID sender = packet.senderId();
        if (refund > 0 && sender != null) {
            EconomyManager.get().deposit(sender, refund / 100.0, reason + " " + packet.id);
            MinecraftServer server = ServerDataManager.getServer();
            ServerPlayer online = server == null ? null : online(server, sender);
            if (online != null) MessageUtil.sendInfo(online, "拼手气红包",
                    "红包 " + packet.id + " 结束，未抢完的 " + EconomyManager.get().format(refund / 100.0) + " 已退回");
        }
    }

    private static ServerPlayer online(MinecraftServer server, UUID uuid) {
        return server == null || uuid == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    private String newId() {
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = UUID.randomUUID().toString().substring(0, 8);
            if (!storage.packets.containsKey(candidate)) return candidate;
        }
        throw new IllegalStateException("无法生成唯一的红包编号");
    }
}
