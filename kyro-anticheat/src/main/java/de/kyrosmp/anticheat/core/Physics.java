package de.kyrosmp.anticheat.core;

public final class Physics {
    public static final double GRAVITY = 0.08;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double SAFE_FALL_DISTANCE = 3.0;

    private Physics() {}

    public static double maxJumpHeight(int jumpAmplifier) {
        return maxJumpHeight(jumpVelocity(Double.NaN, jumpAmplifier), 0.08);
    }

    public static double maxJumpHeight(double v0, double g) {
        double v = Double.isFinite(v0) ? Math.max(0.0, v0) : 0.42;
        g = gravity(g);
        double h = 0.0;

        for (int i = 0; i < 400 && v > 0.0; i++) {
            h = Math.min(Double.MAX_VALUE, h + v);
            v = (v - g) * 0.98;
        }

        return h;
    }

    public static int riseTicks(double v0, double g) {
        double v = Double.isFinite(v0) ? Math.max(0.0, v0) : 0.42;
        g = gravity(g);

        int t;
        for (t = 0; v > 0.0 && t < 400; t++) {
            v = (v - g) * 0.98;
        }

        return t;
    }

    public static double jumpVelocity(double jumpStrengthAttr, int jumpAmplifier) {
        double base =
                Double.isFinite(jumpStrengthAttr) && !(jumpStrengthAttr < 0.0)
                        ? jumpStrengthAttr
                        : 0.42;
        return base + 0.1 * (jumpAmplifier < 0 ? 0.0 : (double) jumpAmplifier + 1.0);
    }

    public static double gravity(double gravityAttr) {
        return Double.isFinite(gravityAttr) && !(gravityAttr < 0.0) ? gravityAttr : 0.08;
    }

    public static double noFallThreshold(int jumpAmplifier, double safeFallAttr) {
        double extraSafe = !Double.isFinite(safeFallAttr) ? 0.0 : Math.max(0.0, safeFallAttr - 3.0);
        return 4.5 + (jumpAmplifier < 0 ? 0.0 : (double) jumpAmplifier + 1.0) + extraSafe;
    }

    public static double elytraClimbBudget(double speed) {
        return Double.isFinite(speed)
                ? Math.min(Double.MAX_VALUE, 24.0 * speed * speed + 6.0)
                : 6.0;
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    public static double timerBalance(double balance, double elapsedMs) {
        return Double.isFinite(balance)
                        && Double.isFinite(elapsedMs)
                        && !(elapsedMs < 0.0)
                        && !(elapsedMs > 1000.0)
                ? Math.max(-400.0, balance + 50.0 - elapsedMs)
                : 0.0;
    }

    public static String suspicionLabel(int flags) {
        if (flags >= 10) {
            return "§cHIGHLY SUSPICIOUS";
        } else if (flags >= 4) {
            return "§eSUSPICIOUS";
        } else {
            return flags > 0 ? "§6WATCH" : "§aCLEAN";
        }
    }
}
