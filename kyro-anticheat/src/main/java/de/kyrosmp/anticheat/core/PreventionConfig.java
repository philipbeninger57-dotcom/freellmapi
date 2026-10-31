package de.kyrosmp.anticheat.core;

import org.bukkit.configuration.ConfigurationSection;

/** Immutable per-plugin policy. Missing keys in old configs use safe 3.6 defaults. */
public record PreventionConfig(boolean enabled, boolean instantSetback, boolean safePositionHistory,
        boolean preventIllegalMovement, boolean preventIllegalActions) {
    public static PreventionConfig defaults() { return new PreventionConfig(true, true, true, true, true); }
    public static PreventionConfig from(ConfigurationSection c) {
        return new PreventionConfig(c.getBoolean("prevention.enabled", true),
                c.getBoolean("prevention.instant-setback", true),
                c.getBoolean("prevention.safe-position-history", true),
                c.getBoolean("prevention.prevent-illegal-movement", true),
                c.getBoolean("prevention.prevent-illegal-actions", true));
    }
    public boolean movement() { return enabled && preventIllegalMovement; }
    public boolean instant() { return movement() && instantSetback; }
    public boolean actions() { return enabled && preventIllegalActions; }
}
