/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViolationLevels {
    private final ConcurrentHashMap<UUID, Map<String, Level>> levels = new ConcurrentHashMap<>();
    private volatile double decayPerMinute;

    public ViolationLevels(double decayPerMinute) {
        this.decayPerMinute = Double.isFinite(decayPerMinute) ? Math.max(0.0, decayPerMinute) : 1.0;
    }

    public void setDecayPerMinute(double decayPerMinute) {
        this.decayPerMinute = Double.isFinite(decayPerMinute) ? Math.max(0.0, decayPerMinute) : 1.0;
    }

    public double add(UUID player, String check, double amount, long now) {
        if (!Double.isFinite(amount) || amount < 0.0) {
            return this.get(player, check, now);
        }
        double[] result = new double[1];
        this.levels.compute(player, (id, inner) -> {
            Map<String, Level> map = inner == null ? new HashMap<>() : inner;
            Level old = map.get(check);
            double base = old == null ? 0.0 : this.decayed(old, now);
            double value = Math.min(Double.MAX_VALUE, base + amount);
            map.put(check, new Level(value, now));
            result[0] = value;
            return map;
        });
        return result[0];
    }

    public double get(UUID player, String check, long now) {
        Map<String, Double> snap = this.snapshot(player, now);
        return snap.getOrDefault(check, 0.0);
    }

    public Map<String, Double> snapshot(UUID player, long now) {
        Map<String, Double> copy = new HashMap<>();
        this.levels.computeIfPresent(player, (id, inner) -> {
            for (Map.Entry<String, Level> e : inner.entrySet()) {
                double v = this.decayed(e.getValue(), now);
                if (!(v > 1.0E-4)) continue;
                copy.put(e.getKey(), v);
            }
            return inner;
        });
        LinkedHashMap<String, Double> sorted = new LinkedHashMap<String, Double>();
        copy.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    public void reset(UUID player) {
        this.levels.remove(player);
    }

    public void cleanup(long now) {
        for (UUID id : this.levels.keySet()) {
            this.levels.computeIfPresent(id, (key, inner) -> {
                inner.entrySet().removeIf(e -> this.decayed(e.getValue(), now) <= 1.0E-4);
                return inner.isEmpty() ? null : inner;
            });
        }
    }

    public int trackedPlayers() {
        return this.levels.size();
    }

    private double decayed(Level level, long now) {
        double minutes = (double)Math.max(0L, now - level.updatedAt()) / 60000.0;
        return Math.max(0.0, level.value() - minutes * this.decayPerMinute);
    }

    private record Level(double value, long updatedAt) {
    }
}

