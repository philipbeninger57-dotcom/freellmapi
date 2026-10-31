/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.Punisher;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class PlayerDataStore {
    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<UUID, PlayerData>();

    public PlayerData get(Player p) {
        PlayerData d = this.data.computeIfAbsent(p.getUniqueId(), id -> new PlayerData((UUID)id, p.getName()));
        d.name = p.getName();
        d.world = p.getWorld() == null ? "?" : p.getWorld().getName();
        d.ping = Punisher.safePing(p);
        return d;
    }

    public PlayerData getQuiet(Player p) {
        return this.data.computeIfAbsent(p.getUniqueId(), id -> new PlayerData((UUID)id, p.getName()));
    }

    public PlayerData peek(UUID id) {
        return this.data.get(id);
    }

    public void remove(UUID id) {
        this.data.remove(id);
    }

    public void cleanupOffline() {
        this.data.keySet().removeIf(id -> Bukkit.getPlayer((UUID)id) == null);
    }

    public int size() {
        return this.data.size();
    }
}

