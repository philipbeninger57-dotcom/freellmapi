package de.kyrosmp.anticheat.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataStore {
    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();

    public PlayerData get(Player p) {
        PlayerData d =
                this.data.computeIfAbsent(p.getUniqueId(), id -> new PlayerData(id, p.getName()));
        d.name = p.getName();
        d.world = p.getWorld() == null ? "?" : p.getWorld().getName();
        d.ping = Punisher.safePing(p);
        return d;
    }

    public PlayerData getQuiet(Player p) {
        return this.data.computeIfAbsent(p.getUniqueId(), id -> new PlayerData(id, p.getName()));
    }

    public PlayerData peek(UUID id) {
        return this.data.get(id);
    }

    public void remove(UUID id) {
        this.data.remove(id);
    }

    public void cleanupOffline() {
        this.data.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    public int size() {
        return this.data.size();
    }
}
