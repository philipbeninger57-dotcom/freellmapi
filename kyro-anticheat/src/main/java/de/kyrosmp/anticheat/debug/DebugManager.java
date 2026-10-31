/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.debug;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class DebugManager {
    private final Plugin plugin;
    private static final String PREFIX = "\u00a78[\u00a7dKYRO-Debug\u00a78] \u00a77";
    private final Map<UUID, Set<UUID>> watchers = new ConcurrentHashMap<UUID, Set<UUID>>();
    private final Map<UUID, Long> lastMoveOutput = new ConcurrentHashMap<UUID, Long>();
    private volatile long moveIntervalMs;

    public DebugManager(Plugin plugin, int maxMoveMessagesPerSecond) {
        this.plugin = plugin;
        this.configure(maxMoveMessagesPerSecond);
    }

    public void configure(int maxMoveMessagesPerSecond) {
        this.moveIntervalMs = 1000L / (long)Math.max(1, Math.min(20, maxMoveMessagesPerSecond));
    }

    public boolean toggle(UUID viewer, UUID target) {
        Set set = this.watchers.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet());
        if (set.remove(viewer)) {
            this.watchers.computeIfPresent(target, (k, s) -> s.isEmpty() ? null : s);
            return false;
        }
        set.add(viewer);
        return true;
    }

    public boolean isWatched(UUID target) {
        Set<UUID> s = this.watchers.get(target);
        return s != null && !s.isEmpty();
    }

    public void move(UUID target, long now, Supplier<String> message) {
        if (!this.isWatched(target)) {
            return;
        }
        Long last = this.lastMoveOutput.get(target);
        if (last != null && now - last < this.moveIntervalMs) {
            return;
        }
        this.lastMoveOutput.put(target, now);
        this.send(target, message.get());
    }

    public void event(UUID target, Supplier<String> message) {
        if (!this.isWatched(target)) {
            return;
        }
        this.send(target, message.get());
    }

    public void removeViewer(UUID viewer) {
        for (UUID target : this.watchers.keySet()) {
            this.watchers.computeIfPresent(target, (k, s) -> {
                s.remove(viewer);
                return s.isEmpty() ? null : s;
            });
        }
    }

    public void removeTarget(UUID target) {
        this.lastMoveOutput.remove(target);
    }

    private void send(UUID target, String text) {
        Set<UUID> set = this.watchers.get(target);
        if (set == null) {
            return;
        }
        String msg = PREFIX + text;
        for (UUID viewerId : set) {
            Player viewer = Bukkit.getPlayer((UUID)viewerId);
            if (viewer == null) continue;
            try {
                viewer.getScheduler().run(this.plugin, task -> {
                    if (viewer.hasPermission("kyroac.debug")) viewer.sendMessage(msg);
                    else this.removeViewer(viewerId);
                }, null);
            } catch (IllegalStateException ignored) {
                // Plugin shutdown: no more debug output.
            }
        }
    }
}

