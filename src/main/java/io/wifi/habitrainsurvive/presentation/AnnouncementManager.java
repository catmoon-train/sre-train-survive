package io.wifi.habitrainsurvive.presentation;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Tick values are server ticks, not wall time; no burst/catch-up broadcasts after idle time or reload. */
public final class AnnouncementManager {
    private int index;
    private long nextAnnouncement = -1;

    public void reset() { index = 0; nextAnnouncement = -1; }

    public void tick(MinecraftServer server, PresentationConfig config, PresentationManager owner) {
        long now = server.getTickCount();
        if (nextAnnouncement < 0) nextAnnouncement = now + config.announcementIntervalSeconds * 20L;
        if (now < nextAnnouncement) return;
        nextAnnouncement = now + config.announcementIntervalSeconds * 20L;
        if (!config.announcementsEnabled || config.announcements.isEmpty() || server.getPlayerCount() == 0) return;
        String message = config.announcements.get(index++ % config.announcements.size());
        if (index == Integer.MAX_VALUE) index = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (PresentationManager.isConnected(player) && owner.preferences(player.getUUID()).announcements) {
                player.sendSystemMessage(Component.literal(owner.render(message, player)));
                owner.playSound(player);
            }
        }
    }

    public void joinOrLeave(ServerPlayer subject, boolean joining, PresentationConfig config, PresentationManager owner) {
        if (!config.joinLeaveEnabled) return;
        String template = joining ? config.joinMessage : config.leaveMessage;
        if (template.isBlank()) return;
        int online = subject.server.getPlayerCount();
        if (!joining && subject.server.getPlayerList().getPlayer(subject.getUUID()) != null) online--;
        Component message = Component.literal(owner.render(template.replace("{online}", Integer.toString(Math.max(0, online))), subject));
        for (ServerPlayer viewer : subject.server.getPlayerList().getPlayers()) {
            if ((joining || viewer != subject) && PresentationManager.isConnected(viewer) && owner.preferences(viewer.getUUID()).announcements) viewer.sendSystemMessage(message);
        }
    }

    public void preview(ServerPlayer player, PresentationConfig config, PresentationManager owner) {
        for (String message : config.announcements) player.sendSystemMessage(Component.literal("§8[预览] " + owner.render(message, player)));
        if (!config.joinMessage.isBlank()) player.sendSystemMessage(Component.literal("§8[加入预览] " + owner.render(config.joinMessage, player)));
        if (!config.leaveMessage.isBlank()) player.sendSystemMessage(Component.literal("§8[离开预览] " + owner.render(config.leaveMessage, player)));
        owner.playSound(player);
    }
}
