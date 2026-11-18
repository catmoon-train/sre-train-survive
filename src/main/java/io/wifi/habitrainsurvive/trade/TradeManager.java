package io.wifi.habitrainsurvive.trade;

import io.wifi.habitrainsurvive.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TradeManager {
    private static final TradeManager INSTANCE = new TradeManager();

    public record PendingTradeRequest(UUID sender, UUID target, long timestamp) {
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 60_000L;
        }
    }

    private final Map<UUID, PendingTradeRequest> requestsByTarget = new ConcurrentHashMap<>();
    private final Map<UUID, TradeSession> activeSessions = new ConcurrentHashMap<>();

    public static TradeManager get() {
        return INSTANCE;
    }

    public void requestTrade(ServerPlayer sender, ServerPlayer target) {
        if (sender.getUUID().equals(target.getUUID())) {
            MessageUtil.sendError(sender, "交易", "您不能与自己交易！");
            return;
        }
        if (activeSessions.containsKey(sender.getUUID()) || activeSessions.containsKey(target.getUUID())) {
            MessageUtil.sendError(sender, "交易", "您或对方正在进行另一场交易。");
            return;
        }
        if (sender.serverLevel() != target.serverLevel() || sender.distanceToSqr(target) > 256.0) {
            MessageUtil.sendError(sender, "交易", "双方距离需在 16 格以内才能发起面对面交易！");
            return;
        }
        requestsByTarget.put(target.getUUID(), new PendingTradeRequest(sender.getUUID(), target.getUUID(), System.currentTimeMillis()));
        MessageUtil.sendSuccess(sender, "交易", "已向 " + target.getName().getString() + " 发送交易请求 (60秒内有效)。");
        target.sendSystemMessage(MessageUtil.prefix("交易", ChatFormatting.AQUA)
                .append(Component.literal(sender.getName().getString() + " 请求与您进行面对面交易 ").withStyle(ChatFormatting.YELLOW))
                .append(MessageUtil.commandButton("接受交易", "/trade accept", "点击打开交易窗口", ChatFormatting.GREEN))
                .append(Component.literal(" "))
                .append(MessageUtil.commandButton("拒绝", "/trade deny", "点击拒绝交易请求", ChatFormatting.RED)));
        MessageUtil.playDing(target);
    }

    public void acceptTrade(ServerPlayer target) {
        PendingTradeRequest req = requestsByTarget.remove(target.getUUID());
        if (req == null || req.isExpired()) {
            MessageUtil.sendError(target, "交易", "没有待处理的交易请求或请求已超时。");
            return;
        }
        ServerPlayer sender = target.server.getPlayerList().getPlayer(req.sender());
        if (sender == null) {
            MessageUtil.sendError(target, "交易", "发起交易的玩家已离线。");
            return;
        }
        if (sender.serverLevel() != target.serverLevel() || sender.distanceToSqr(target) > 256.0) {
            MessageUtil.sendError(target, "交易", "双方距离超过 16 格，无法开启交易界面。");
            return;
        }
        TradeSession session = new TradeSession(sender, target);
        activeSessions.put(sender.getUUID(), session);
        activeSessions.put(target.getUUID(), session);
        session.openMenus();
    }

    public void denyTrade(ServerPlayer target) {
        PendingTradeRequest req = requestsByTarget.remove(target.getUUID());
        if (req == null) {
            MessageUtil.sendError(target, "交易", "没有待处理的交易请求。");
            return;
        }
        ServerPlayer sender = target.server.getPlayerList().getPlayer(req.sender());
        if (sender != null) {
            MessageUtil.sendError(sender, "交易", target.getName().getString() + " 拒绝了您的交易请求。");
        }
        MessageUtil.sendInfo(target, "交易", "已拒绝该交易请求。");
    }

    public void removeSession(UUID a, UUID b) {
        activeSessions.remove(a);
        activeSessions.remove(b);
    }
}
