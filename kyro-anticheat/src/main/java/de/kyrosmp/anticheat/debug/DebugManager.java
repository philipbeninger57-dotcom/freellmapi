package de.kyrosmp.anticheat.debug;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class DebugManager {
    private final Plugin plugin;
    private static final String PREFIX = "§8[§dKYRO-Debug§8] §7";
    private final Map<UUID, Set<UUID>> watchers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveOutput = new ConcurrentHashMap<>();
    private volatile long moveIntervalMs;

    public DebugManager(Plugin plugin, int maxMoveMessagesPerSecond) {
        this.plugin = plugin;
        this.configure(maxMoveMessagesPerSecond);
    }

    public void configure(int maxMoveMessagesPerSecond) {
        this.moveIntervalMs = 1000L / (long) Math.max(1, Math.min(20, maxMoveMessagesPerSecond));
    }

    public boolean toggle(UUID viewer, UUID target) {
        Set set = this.watchers.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet());
        if (set.remove(viewer)) {
            this.watchers.computeIfPresent(target, (k, s) -> s.isEmpty() ? null : s);
            return false;
        } else {
            set.add(viewer);
            return true;
        }
    }

    public boolean isWatched(UUID target) {
        Set<UUID> s = this.watchers.get(target);
        return s != null && !s.isEmpty();
    }

    public void move(UUID target, long now, Supplier<String> message) {
        if (this.isWatched(target)) {
            Long last = this.lastMoveOutput.get(target);
            if (last == null || now - last >= this.moveIntervalMs) {
                this.lastMoveOutput.put(target, now);
                this.send(target, message.get());
            }
        }
    }

    public void event(UUID target, Supplier<String> message) {
        if (this.isWatched(target)) {
            this.send(target, message.get());
        }
    }

    public void removeViewer(UUID viewer) {
        for (UUID target : this.watchers.keySet()) {
            this.watchers.computeIfPresent(
                    target,
                    (k, s) -> {
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
        if (set != null) {
            String msg = "§8[§dKYRO-Debug§8] §7" + text;

            for (UUID viewerId : set) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    try {
                        viewer.getScheduler()
                                .run(
                                        this.plugin,
                                        task -> {
                                            if (viewer.hasPermission("kyroac.debug")) {
                                                viewer.sendMessage(msg);
                                            } else {
                                                this.removeViewer(viewerId);
                                            }
                                        },
                                        null);
                    } catch (IllegalStateException ignored) {
                    }
                }
            }
        }
    }
}
