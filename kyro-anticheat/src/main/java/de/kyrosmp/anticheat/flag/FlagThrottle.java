/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.flag;

import de.kyrosmp.anticheat.flag.Confidence;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FlagThrottle {
    private final ConcurrentHashMap<Key, State> states = new ConcurrentHashMap();
    private volatile long duplicateWindowMs;
    private volatile long mergeWindowMs;

    public FlagThrottle(long duplicateWindowMs, long mergeWindowMs) {
        this.configure(duplicateWindowMs, mergeWindowMs);
    }

    public void configure(long duplicateWindowMs, long mergeWindowMs) {
        this.duplicateWindowMs = Math.max(0L, duplicateWindowMs);
        this.mergeWindowMs = Math.max(0L, mergeWindowMs);
    }

    public boolean isDuplicate(UUID player, String check, long now) {
        boolean[] dup = new boolean[1];
        this.states.compute(new Key(player, check), (k, s) -> {
            State st;
            State state = st = s == null ? new State() : s;
            if (now >= st.lastAcceptedAt && now - st.lastAcceptedAt < this.duplicateWindowMs) {
                dup[0] = true;
            } else {
                st.lastAcceptedAt = now;
            }
            return st;
        });
        return dup[0];
    }

    public Decision register(UUID player, String check, long now, Confidence confidence, String candidateId) {
        Decision[] out = new Decision[1];
        this.states.compute(new Key(player, check), (k, s) -> {
            State st;
            State state = st = s == null ? new State() : s;
            if (st.lastStoredId != null && now >= st.lastStoredAt && now - st.lastStoredAt < this.mergeWindowMs) {
                st.storedConfidence = Confidence.max(st.storedConfidence, confidence);
                out[0] = new Decision(Action.MERGE, st.lastStoredId, st.storedConfidence);
            } else {
                st.lastStoredId = candidateId;
                st.lastStoredAt = now;
                st.storedConfidence = confidence;
                out[0] = new Decision(Action.STORE, candidateId, confidence);
            }
            return st;
        });
        return out[0];
    }

    public void reset(UUID player) {
        this.states.keySet().removeIf(k -> k.player().equals(player));
    }

    public void cleanup(long now, long idleMs) {
        this.states.entrySet().removeIf(e -> now - ((State)e.getValue()).lastAcceptedAt > idleMs && now - ((State)e.getValue()).lastStoredAt > idleMs);
    }

    public int size() {
        return this.states.size();
    }

    private record Key(UUID player, String check) {
    }

    public record Decision(Action action, String flagId, Confidence confidence) {
    }

    private static final class State {
        long lastAcceptedAt = -4611686018427387904L;
        long lastStoredAt = -4611686018427387904L;
        String lastStoredId;
        Confidence storedConfidence;

        private State() {
        }
    }

    public static enum Action {
        STORE,
        MERGE;

    }
}

