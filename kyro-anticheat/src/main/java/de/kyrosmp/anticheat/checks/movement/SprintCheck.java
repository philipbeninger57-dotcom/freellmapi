package de.kyrosmp.anticheat.checks.movement;

import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Sprint A: starting to sprint while too hungry to sprint.
 *
 * <p>A vanilla client only sends a sprint start with more than 6 food points (or when flight is
 * allowed). Hunger/omni-sprint cheats send it anyway to get the 30 % sprint speed. The start is
 * refused, so the server never applies the sprint speed modifier, and {@link PredictionChecks} no
 * longer grants sprint speed to a hungry player that is not sprinting server-side.
 *
 * <p>The food level can drop on the server while the client still sees the old value, so a start
 * within ping + 300 ms of the last time the player had more than 6 food is not judged.
 */
public final class SprintCheck {
    static final long FOOD_RACE_BASE_MS = 300L;

    private final FlagSink sink;
    private volatile StrictConfig strict;
    private volatile PreventionConfig prevention;

    public SprintCheck(FlagSink sink, StrictConfig strict, PreventionConfig prevention) {
        this.sink = sink;
        this.strict = strict;
        this.prevention = prevention;
    }

    /** Remembers that the player had enough food to sprint (move events, food changes). */
    public static void noteFood(PlayerData d, int foodLevel, boolean mayFly, long now) {
        if (mayFly || foodLevel > PredictionChecks.SPRINT_FOOD_LEVEL) {
            d.lastFoodAbove6At = now;
        }
    }

    /**
     * Judges a sprint start.
     *
     * @return {@code true} if the start must be refused
     */
    public boolean onSprintStart(
            Player p, PlayerData d, int foodLevel, boolean mayFly, int ping, long now) {
        if (!this.strict.sprintEnabled()) {
            return false;
        }
        if (mayFly || foodLevel > PredictionChecks.SPRINT_FOOD_LEVEL) {
            d.lastFoodAbove6At = now;
            d.sprintBuffer = Math.max(0.0, d.sprintBuffer - 0.25);
            return false;
        }

        long race = FOOD_RACE_BASE_MS + Math.min(1000L, Math.max(0L, ping));
        if (d.lastFoodAbove6At != 0L && now - d.lastFoodAbove6At <= race) {
            return false;
        }

        d.sprintBuffer++;
        boolean refuse = this.prevention.actions();
        if (d.sprintBuffer >= this.strict.sprintFlagAt()) {
            String id =
                    this.sink.flag(
                            p,
                            d,
                            "Sprint A",
                            "Started sprinting while too hungry to sprint",
                            String.format(
                                    Locale.US,
                                    "food=%d; required>%d; buffer=%.1f; ping=%d",
                                    foodLevel,
                                    PredictionChecks.SPRINT_FOOD_LEVEL,
                                    d.sprintBuffer,
                                    ping));
            if (id != null && refuse) {
                this.sink.action(id, "Sprint refused");
            }
            d.sprintBuffer = 1.0;
        }
        return refuse;
    }
}
