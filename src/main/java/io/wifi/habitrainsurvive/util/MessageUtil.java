package io.wifi.habitrainsurvive.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public class MessageUtil {
    public static MutableComponent prefix(String module, ChatFormatting color) {
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(module).withStyle(color, ChatFormatting.BOLD))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
    }

    public static void sendSuccess(ServerPlayer player, String module, String message) {
        player.sendSystemMessage(prefix(module, ChatFormatting.AQUA)
                .append(Component.literal(message).withStyle(ChatFormatting.GREEN)));
    }

    public static void sendError(ServerPlayer player, String module, String message) {
        player.sendSystemMessage(prefix(module, ChatFormatting.RED)
                .append(Component.literal(message).withStyle(ChatFormatting.RED)));
    }

    public static void sendInfo(ServerPlayer player, String module, String message) {
        player.sendSystemMessage(prefix(module, ChatFormatting.GOLD)
                .append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
    }

    public static MutableComponent commandButton(String label, String command, String hoverText, ChatFormatting color) {
        return Component.literal("[" + label + "]")
                .withStyle(style -> style
                        .withColor(color)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hoverText))));
    }

    public static void playDing(ServerPlayer player) {
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f);
    }

    public static void playError(ServerPlayer player) {
        player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.8f, 1.0f);
    }
}
