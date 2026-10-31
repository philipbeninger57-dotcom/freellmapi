/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.core;

public final class Physics {
    public static final double GRAVITY = 0.08;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double SAFE_FALL_DISTANCE = 3.0;

    private Physics() {
    }

    public static double maxJumpHeight(int jumpAmplifier) {
        return Physics.maxJumpHeight(Physics.jumpVelocity(Double.NaN, jumpAmplifier), 0.08);
    }

    public static double maxJumpHeight(double v0, double g) {
        double v = Double.isFinite(v0) ? Math.max(0.0, v0) : JUMP_VELOCITY;
        g = gravity(g);
        double h = 0.0;
        for (int i = 0; i < 400 && v > 0.0; ++i) {
            h = Math.min(Double.MAX_VALUE, h + v);
            v = (v - g) * 0.98;
        }
        return h;
    }

    public static int riseTicks(double v0, double g) {
        int t;
        double v = Double.isFinite(v0) ? Math.max(0.0, v0) : JUMP_VELOCITY;
        g = gravity(g);
        for (t = 0; v > 0.0 && t < 400; ++t) {
            v = (v - g) * 0.98;
        }
        return t;
    }

    public static double jumpVelocity(double jumpStrengthAttr, int jumpAmplifier) {
        double base = !Double.isFinite(jumpStrengthAttr) || jumpStrengthAttr < 0.0 ? 0.42 : jumpStrengthAttr;
        return base + 0.1 * (jumpAmplifier < 0 ? 0.0 : (double)jumpAmplifier + 1.0);
    }

    public static double gravity(double gravityAttr) {
        return !Double.isFinite(gravityAttr) || gravityAttr < 0.0 ? 0.08 : gravityAttr;
    }

    public static double noFallThreshold(int jumpAmplifier, double safeFallAttr) {
        double extraSafe = !Double.isFinite(safeFallAttr) ? 0.0 : Math.max(0.0, safeFallAttr - 3.0);
        return 4.5 + (jumpAmplifier < 0 ? 0.0 : (double)jumpAmplifier + 1.0) + extraSafe;
    }

    public static double elytraClimbBudget(double speed) {
        return Double.isFinite(speed) ? Math.min(Double.MAX_VALUE, 24.0 * speed * speed + 6.0) : 6.0;
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    public static double timerBalance(double balance, double elapsedMs) {
        if (!Double.isFinite(balance) || !Double.isFinite(elapsedMs) || elapsedMs < 0.0 || elapsedMs > 1000.0) {
            return 0.0;
        }
        return Math.max(-400.0, balance + 50.0 - elapsedMs);
    }

    public static String suspicionLabel(int flags) {
        if (flags >= 10) {
            return "\u00a7cHIGHLY SUSPICIOUS";
        }
        if (flags >= 4) {
            return "\u00a7eSUSPICIOUS";
        }
        if (flags > 0) {
            return "\u00a76WATCH";
        }
        return "\u00a7aCLEAN";
    }
}

