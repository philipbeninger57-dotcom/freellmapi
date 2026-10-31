package de.kyrosmp.anticheat.core;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface SetbackService {
    boolean setback(Player player, PlayerData data, String flagId);

    boolean setbackTo(Player player, PlayerData data, Location target, String flagId);

    void registerFlyPunishment(Player player, PlayerData data, long now, String flagId);
}
