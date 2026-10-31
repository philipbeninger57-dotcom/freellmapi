package de.kyrosmp.anticheat.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.function.Consumer;

public final class HoverListener implements Listener {
    private final Consumer<Player> start;

    public HoverListener(Consumer<Player> start) {
        this.start = start;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        this.start.accept(e.getPlayer());
    }
}
