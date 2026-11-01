package de.kyrosmp.anticheat.flag;

import de.kyrosmp.anticheat.core.PlayerData;

import org.bukkit.entity.Player;

public interface FlagSink {
    String flag(Player player, PlayerData data, String check, String reason, String details);

    void action(String flagId, String action);
}
