package de.kyrosmp.anticheat.storage;

public record PlayerFlagStats(
        int total,
        int triggers,
        int newCount,
        int reviewedCount,
        int resolvedCount,
        long lastFlagAt) {
    public static final PlayerFlagStats EMPTY = new PlayerFlagStats(0, 0, 0, 0, 0, 0L);
}
