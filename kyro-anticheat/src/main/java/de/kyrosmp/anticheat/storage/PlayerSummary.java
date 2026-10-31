/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.storage;

import de.kyrosmp.anticheat.flag.FlagStatus;
import java.util.UUID;

public record PlayerSummary(UUID uuid, String name, int flagCount, int triggerCount, int currentCount, int newCount, int reviewedCount, double maxOpenVl, long lastFlagAt, String lastCheck) {
    public FlagStatus status() {
        if (this.newCount > 0) {
            return FlagStatus.NEW;
        }
        if (this.reviewedCount > 0) {
            return FlagStatus.REVIEWED;
        }
        return FlagStatus.RESOLVED;
    }
}

