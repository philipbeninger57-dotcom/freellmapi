package de.kyrosmp.anticheat.core;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Settings of the 3.7 prediction and prevention layer. Kept separate from {@link CheckConfig}
 * so older configurations and the existing record stay untouched. Missing keys use these defaults.
 */
public record StrictConfig(
        boolean flyPredictionEnabled, double flyPredictionTolerance, double flyPredictionFlagAt,
        boolean hoverEnabled, long hoverStillMs,
        boolean stepEnabled, double stepFlagAt,
        boolean speedPredictionEnabled, double speedPredictionTolerance, double speedPredictionFlagAt,
        boolean noSlowEnabled, double noSlowFlagAt,
        boolean nukerEnabled, int nukerMaxPerSecond, double nukerFlagAt,
        boolean ghostHandEnabled, double ghostHandFlagAt,
        boolean scaffoldFaceEnabled, double scaffoldFaceFlagAt,
        boolean criticalsPrevent, boolean reachInstantCancel, boolean autoClickerCancel,
        boolean inventoryMoveCancel, boolean protectSetbacks, boolean groundFallback,
        boolean timerPrevent, long inventoryOpenGraceMs) {

    /** Default grace after an inventory opens before movement samples count (3.7.2). */
    public static final long DEFAULT_INVENTORY_OPEN_GRACE_MS = 25L;

    /** 3.7.0/3.7.1 field order; the 3.7.2 switches use their defaults. */
    public StrictConfig(
            boolean flyPredictionEnabled, double flyPredictionTolerance, double flyPredictionFlagAt,
            boolean hoverEnabled, long hoverStillMs,
            boolean stepEnabled, double stepFlagAt,
            boolean speedPredictionEnabled, double speedPredictionTolerance, double speedPredictionFlagAt,
            boolean noSlowEnabled, double noSlowFlagAt,
            boolean nukerEnabled, int nukerMaxPerSecond, double nukerFlagAt,
            boolean ghostHandEnabled, double ghostHandFlagAt,
            boolean scaffoldFaceEnabled, double scaffoldFaceFlagAt,
            boolean criticalsPrevent, boolean reachInstantCancel, boolean autoClickerCancel,
            boolean inventoryMoveCancel, boolean protectSetbacks, boolean groundFallback) {
        this(flyPredictionEnabled, flyPredictionTolerance, flyPredictionFlagAt, hoverEnabled, hoverStillMs,
                stepEnabled, stepFlagAt, speedPredictionEnabled, speedPredictionTolerance, speedPredictionFlagAt,
                noSlowEnabled, noSlowFlagAt, nukerEnabled, nukerMaxPerSecond, nukerFlagAt, ghostHandEnabled,
                ghostHandFlagAt, scaffoldFaceEnabled, scaffoldFaceFlagAt, criticalsPrevent, reachInstantCancel,
                autoClickerCancel, inventoryMoveCancel, protectSetbacks, groundFallback,
                false, DEFAULT_INVENTORY_OPEN_GRACE_MS);
    }

    public static StrictConfig defaults() {
        return new StrictConfig(
                true, 0.03, 3.0,
                true, 1200L,
                true, 2.0,
                true, 0.04, 4.0,
                true, 4.0,
                true, 25, 3.0,
                true, 3.0,
                true, 3.0,
                true, true, true,
                true, false, true,
                false, DEFAULT_INVENTORY_OPEN_GRACE_MS);
    }

    public static StrictConfig from(ConfigurationSection c) {
        StrictConfig d = defaults();
        return new StrictConfig(
                c.getBoolean("checks.fly.prediction.enabled", d.flyPredictionEnabled),
                clamp(c.getDouble("checks.fly.prediction.tolerance", d.flyPredictionTolerance), 0.005, 0.5),
                clamp(c.getDouble("checks.fly.prediction.flag-at", d.flyPredictionFlagAt), 1.0, 100.0),
                c.getBoolean("checks.fly.hover.enabled", d.hoverEnabled),
                (long) clamp(c.getLong("checks.fly.hover.still-ms", d.hoverStillMs), 600, 10000),
                c.getBoolean("checks.step.enabled", d.stepEnabled),
                clamp(c.getDouble("checks.step.buffer.flag-at", d.stepFlagAt), 1.0, 100.0),
                c.getBoolean("checks.speed.prediction.enabled", d.speedPredictionEnabled),
                clamp(c.getDouble("checks.speed.prediction.tolerance", d.speedPredictionTolerance), 0.01, 1.0),
                clamp(c.getDouble("checks.speed.prediction.flag-at", d.speedPredictionFlagAt), 1.0, 100.0),
                c.getBoolean("checks.noslow.enabled", d.noSlowEnabled),
                clamp(c.getDouble("checks.noslow.buffer.flag-at", d.noSlowFlagAt), 1.0, 100.0),
                c.getBoolean("checks.nuker.enabled", d.nukerEnabled),
                (int) clamp(c.getInt("checks.nuker.max-per-second", d.nukerMaxPerSecond), 5, 200),
                clamp(c.getDouble("checks.nuker.buffer.flag-at", d.nukerFlagAt), 1.0, 100.0),
                c.getBoolean("checks.ghosthand.enabled", d.ghostHandEnabled),
                clamp(c.getDouble("checks.ghosthand.buffer.flag-at", d.ghostHandFlagAt), 1.0, 100.0),
                c.getBoolean("checks.scaffold.face.enabled", d.scaffoldFaceEnabled),
                clamp(c.getDouble("checks.scaffold.face.buffer.flag-at", d.scaffoldFaceFlagAt), 1.0, 100.0),
                c.getBoolean("checks.criticals.prevent", d.criticalsPrevent),
                c.getBoolean("checks.reach.instant-cancel", d.reachInstantCancel),
                c.getBoolean("checks.autoclicker.cancel-hits", d.autoClickerCancel),
                c.getBoolean("checks.inventorymove.cancel-clicks", d.inventoryMoveCancel),
                c.getBoolean("prevention.protect-setbacks", d.protectSetbacks),
                c.getBoolean("prevention.ground-fallback", d.groundFallback),
                c.getBoolean("checks.timer.prevent", d.timerPrevent),
                (long) clamp(c.getLong("checks.inventorymove.open-grace-ms", d.inventoryOpenGraceMs), 0, 1000));
    }

    private static double clamp(double v, double min, double max) {
        if (!Double.isFinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }
}
