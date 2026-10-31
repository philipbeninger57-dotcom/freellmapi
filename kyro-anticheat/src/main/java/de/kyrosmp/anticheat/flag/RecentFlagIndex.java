/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.flag;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RecentFlagIndex {
    private final ConcurrentHashMap<UUID, ArrayDeque<Entry>> entries = new ConcurrentHashMap();
    private final int maxPerPlayer;

    public RecentFlagIndex(int maxPerPlayer) {
        this.maxPerPlayer = Math.max(8, maxPerPlayer);
    }

    public Stats addAndStats(UUID player, String check, String group, long now, long windowMs) {
        Stats[] out = new Stats[1];
        this.entries.compute(player, (id, dq) -> {
            ArrayDeque q = dq == null ? new ArrayDeque() : dq;
            RecentFlagIndex.prune(q, now - windowMs);
            q.addLast(new Entry(now, check, group));
            while (q.size() > this.maxPerPlayer) {
                q.removeFirst();
            }
            out[0] = RecentFlagIndex.stats(q, check);
            return q;
        });
        return out[0];
    }

    public Stats stats(UUID player, String check, long now, long windowMs) {
        Stats[] out = new Stats[]{new Stats(0, 0, 0)};
        this.entries.computeIfPresent(player, (id, q) -> {
            RecentFlagIndex.prune(q, now - windowMs);
            out[0] = RecentFlagIndex.stats(q, check);
            return q.isEmpty() ? null : q;
        });
        return out[0];
    }

    public void reset(UUID player) {
        this.entries.remove(player);
    }

    public void cleanup(long now, long windowMs) {
        for (UUID id : this.entries.keySet()) {
            this.entries.computeIfPresent(id, (k, q) -> {
                RecentFlagIndex.prune(q, now - windowMs);
                return q.isEmpty() ? null : q;
            });
        }
    }

    public int trackedPlayers() {
        return this.entries.size();
    }

    private static void prune(ArrayDeque<Entry> q, long minTime) {
        while (!q.isEmpty() && q.peekFirst().time() < minTime) {
            q.removeFirst();
        }
    }

    private static Stats stats(ArrayDeque<Entry> q, String check) {
        int reps = 0;
        HashSet<String> groups = new HashSet<String>();
        for (Entry e : q) {
            if (e.check().equals(check)) {
                ++reps;
            }
            groups.add(e.group());
        }
        return new Stats(reps, q.size(), groups.size());
    }

    public record Stats(int repetitions, int total, int independentGroups) {
    }

    private record Entry(long time, String check, String group) {
    }
}

