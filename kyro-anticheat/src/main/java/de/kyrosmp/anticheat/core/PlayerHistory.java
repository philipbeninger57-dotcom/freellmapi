/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.Physics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PlayerHistory {
    private final MoveSample[] moves;
    private final AttackSample[] attacks;
    private int moveHead;
    private int moveSize;
    private int attackHead;
    private int attackSize;

    public PlayerHistory(int moveCapacity, int attackCapacity) {
        this.moves = new MoveSample[Math.max(1, moveCapacity)];
        this.attacks = new AttackSample[Math.max(1, attackCapacity)];
    }

    public synchronized void addMove(MoveSample sample) {
        this.moves[this.moveHead] = sample;
        this.moveHead = (this.moveHead + 1) % this.moves.length;
        if (this.moveSize < this.moves.length) {
            ++this.moveSize;
        }
    }

    public synchronized void addAttack(AttackSample sample) {
        this.attacks[this.attackHead] = sample;
        this.attackHead = (this.attackHead + 1) % this.attacks.length;
        if (this.attackSize < this.attacks.length) {
            ++this.attackSize;
        }
    }

    public synchronized List<MoveSample> moves() {
        ArrayList<MoveSample> out = new ArrayList<MoveSample>(this.moveSize);
        int start = (this.moveHead - this.moveSize + this.moves.length) % this.moves.length;
        for (int i = 0; i < this.moveSize; ++i) {
            out.add(this.moves[(start + i) % this.moves.length]);
        }
        return out;
    }

    public synchronized List<AttackSample> attacks() {
        ArrayList<AttackSample> out = new ArrayList<AttackSample>(this.attackSize);
        int start = (this.attackHead - this.attackSize + this.attacks.length) % this.attacks.length;
        for (int i = 0; i < this.attackSize; ++i) {
            out.add(this.attacks[(start + i) % this.attacks.length]);
        }
        return out;
    }

    public synchronized double minHitboxDistance(long since, double ex, double ey, double ez, double halfWidth, double height, double margin) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i <= this.moveSize; ++i) {
            MoveSample s = this.moves[(this.moveHead - i + this.moves.length) % this.moves.length];
            if (s.time() < since) break;
            double d = PlayerHistory.boxDistance(ex, ey, ez, s.x() - halfWidth - margin, s.y() - margin, s.z() - halfWidth - margin, s.x() + halfWidth + margin, s.y() + height + margin, s.z() + halfWidth + margin);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    public static double boxDistance(double px, double py, double pz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double cx = Physics.clamp(px, minX, maxX);
        double cy = Physics.clamp(py, minY, maxY);
        double cz = Physics.clamp(pz, minZ, maxZ);
        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public Summary summary(long now, long windowMs) {
        long min = now - windowMs;
        int n = 0;
        int grounded = 0;
        double maxH = 0.0;
        double sumH = 0.0;
        double maxUp = 0.0;
        double maxDown = 0.0;
        for (MoveSample s : this.moves()) {
            if (s.time() < min) continue;
            ++n;
            maxH = Math.max(maxH, s.horizontal());
            sumH += s.horizontal();
            maxUp = Math.max(maxUp, s.dy());
            maxDown = Math.min(maxDown, s.dy());
            if (!s.grounded()) continue;
            ++grounded;
        }
        int atk = 0;
        HashSet<UUID> targets = new HashSet<UUID>();
        for (AttackSample a : this.attacks()) {
            if (a.time() < min) continue;
            ++atk;
            targets.add(a.target());
        }
        return new Summary(n, maxH, n == 0 ? 0.0 : sumH / (double)n, maxUp, maxDown, grounded, atk, targets.size());
    }

    public synchronized void clearMoves() {
        Arrays.fill(this.moves, null);
        this.moveHead = 0;
        this.moveSize = 0;
    }

    public synchronized void clear() {
        this.attackSize = 0;
        this.attackHead = 0;
        this.moveSize = 0;
        this.moveHead = 0;
        Arrays.fill(this.moves, null);
        Arrays.fill(this.attacks, null);
    }

    public record MoveSample(long time, double x, double y, double z, float yaw, float pitch, double dy, double horizontal, boolean grounded, int ping) {
    }

    public record AttackSample(long time, UUID target, double distance) {
    }

    public record Summary(int samples, double maxHorizontal, double avgHorizontal, double maxUp, double maxDown, int groundedSamples, int attacks, int distinctTargets) {
        public String compact() {
            return String.format(Locale.ROOT, "hist[n=%d h_max=%.3f h_avg=%.3f dy_max=%.3f dy_min=%.3f ground=%d atk=%d targets=%d]", this.samples, this.maxHorizontal, this.avgHorizontal, this.maxUp, this.maxDown, this.groundedSamples, this.attacks, this.distinctTargets);
        }
    }
}

