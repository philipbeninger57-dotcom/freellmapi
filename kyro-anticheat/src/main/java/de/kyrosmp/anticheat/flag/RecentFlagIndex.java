package de.kyrosmp.anticheat.flag;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RecentFlagIndex {
    private final ConcurrentHashMap<UUID, ArrayDeque<RecentFlagIndex.Entry>> entries =
            new ConcurrentHashMap<>();
    private final int maxPerPlayer;

    public RecentFlagIndex(int maxPerPlayer) {
        this.maxPerPlayer = Math.max(8, maxPerPlayer);
    }

    public RecentFlagIndex.Stats addAndStats(
            UUID player, String check, String group, long now, long windowMs) {
        RecentFlagIndex.Stats[] out = new RecentFlagIndex.Stats[1];
        this.entries.compute(
                player,
                (id, dq) -> {
                    ArrayDeque q = dq == null ? new ArrayDeque() : dq;
                    prune(q, now - windowMs);
                    q.addLast(new RecentFlagIndex.Entry(now, check, group));

                    while (q.size() > this.maxPerPlayer) {
                        q.removeFirst();
                    }

                    out[0] = stats(q, check);
                    return q;
                });
        return out[0];
    }

    public RecentFlagIndex.Stats stats(UUID player, String check, long now, long windowMs) {
        RecentFlagIndex.Stats[] out =
                new RecentFlagIndex.Stats[] {new RecentFlagIndex.Stats(0, 0, 0)};
        this.entries.computeIfPresent(
                player,
                (id, q) -> {
                    prune((ArrayDeque<RecentFlagIndex.Entry>) q, now - windowMs);
                    out[0] = stats((ArrayDeque<RecentFlagIndex.Entry>) q, check);
                    return q.isEmpty() ? null : q;
                });
        return out[0];
    }

    public void reset(UUID player) {
        this.entries.remove(player);
    }

    public void cleanup(long now, long windowMs) {
        for (UUID id : this.entries.keySet()) {
            this.entries.computeIfPresent(
                    id,
                    (k, q) -> {
                        prune((ArrayDeque<RecentFlagIndex.Entry>) q, now - windowMs);
                        return q.isEmpty() ? null : q;
                    });
        }
    }

    public int trackedPlayers() {
        return this.entries.size();
    }

    private static void prune(ArrayDeque<RecentFlagIndex.Entry> q, long minTime) {
        while (!q.isEmpty() && q.peekFirst().time() < minTime) {
            q.removeFirst();
        }
    }

    private static RecentFlagIndex.Stats stats(ArrayDeque<RecentFlagIndex.Entry> q, String check) {
        int reps = 0;
        HashSet<String> groups = new HashSet<>();

        for (RecentFlagIndex.Entry e : q) {
            if (e.check().equals(check)) {
                reps++;
            }

            groups.add(e.group());
        }

        return new RecentFlagIndex.Stats(reps, q.size(), groups.size());
    }

    private static record Entry(long time, String check, String group) {}

    public static record Stats(int repetitions, int total, int independentGroups) {}
}
