package de.kyrosmp.anticheat.listener;

import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Starts the per-player Fly C heartbeat (3.7) on the player's own scheduler. */
public final class HoverListener implements Listener {
    private final Consumer<Player> start;

    public HoverListener(Consumer<Player> start) {
        this.start = start;
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        this.start.accept(e.getPlayer());
    }
}
