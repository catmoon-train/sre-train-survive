package io.wifi.habitrainsurvive.presentation;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Group/server information in a personal header/footer; leaves other mods' names and teams alone. */
public final class TabManager {
    private record View(String header, String footer) { }
    private final Map<UUID, View> views = new HashMap<>();

    public void update(ServerPlayer player, PresentationConfig config, boolean visible, UnaryOperator<String> render) {
        if (!visible) { hide(player); return; }
        View next = new View(render.apply(config.tabHeader), render.apply(config.tabFooter));
        if (next.equals(views.get(player.getUUID()))) return;
        player.connection.send(new ClientboundTabListPacket(Component.literal(next.header), Component.literal(next.footer)));
        views.put(player.getUUID(), next);
    }

    public void hide(ServerPlayer player) {
        if (views.remove(player.getUUID()) != null && PresentationManager.isConnected(player)) {
            player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
        }
    }

    public void forget(UUID uuid) { views.remove(uuid); }
    public void clear() { views.clear(); }
}
