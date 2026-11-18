package io.wifi.habitrainsurvive.presentation;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Client-only private objective. Never resets, removes or mutates server/mod objectives or teams. */
public final class SidebarManager {
    public static final String OBJECTIVE = "habi:operations";
    private record View(Objective objective, String title, List<String> lines) { }
    private final Map<UUID, View> views = new HashMap<>();

    public void update(ServerPlayer player, PresentationConfig config, boolean visible, UnaryOperator<String> render) {
        if (!visible || hasForeignSidebar(player)) { hide(player); return; }
        String title = render.apply(config.sidebarTitle);
        List<String> lines = config.sidebarLines.stream().map(render).toList();
        View old = views.get(player.getUUID());
        Objective objective;
        if (old == null) {
            objective = new Objective(new Scoreboard(), OBJECTIVE, ObjectiveCriteria.DUMMY, Component.literal(title),
                    ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
            player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        } else {
            objective = old.objective;
            if (!title.equals(old.title)) {
                objective.setDisplayName(Component.literal(title));
                player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_CHANGE));
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            if (old == null || old.lines.size() != lines.size() || !lines.get(i).equals(old.lines.get(i))) {
                player.connection.send(new ClientboundSetScorePacket("habi:line_" + i, OBJECTIVE, lines.size() - i,
                        Optional.of(Component.literal(lines.get(i))), Optional.of(BlankFormat.INSTANCE)));
            }
        }
        if (old != null) for (int i = lines.size(); i < old.lines.size(); i++) {
            player.connection.send(new ClientboundResetScorePacket("habi:line_" + i, OBJECTIVE));
        }
        views.put(player.getUUID(), new View(objective, title, lines));
    }

    private boolean hasForeignSidebar(ServerPlayer player) {
        var board = player.server.getScoreboard();
        // Yield to vanilla/server-side mod displays, including the player's team-coloured sidebar.
        if (board.getDisplayObjective(DisplaySlot.SIDEBAR) != null || board.getObjective(OBJECTIVE) != null) return true;
        var team = board.getPlayersTeam(player.getScoreboardName());
        if (team != null && team.getColor().isColor()) {
            DisplaySlot slot = DisplaySlot.teamColorToSlot(team.getColor());
            return slot != null && board.getDisplayObjective(slot) != null;
        }
        return false;
    }

    public void hide(ServerPlayer player) {
        View view = views.remove(player.getUUID());
        // Removing our private objective is sufficient; never send an unconditional SIDEBAR=null packet.
        if (view != null && PresentationManager.isConnected(player)) {
            player.connection.send(new ClientboundSetObjectivePacket(view.objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
        }
    }

    public void forget(UUID uuid) { views.remove(uuid); }
    public void clear() { views.clear(); }
}
