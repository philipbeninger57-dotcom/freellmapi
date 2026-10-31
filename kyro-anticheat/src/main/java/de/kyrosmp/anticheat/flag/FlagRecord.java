/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.flag;

import de.kyrosmp.anticheat.core.CheckCategory;
import de.kyrosmp.anticheat.flag.Confidence;
import de.kyrosmp.anticheat.flag.FlagStatus;
import java.util.UUID;

public record FlagRecord(String flagId, UUID playerUuid, String playerName, CheckCategory category, String checkName, double vl, long createdAt, String reason, String details, int ping, Confidence confidence, String world, double x, double y, double z, FlagStatus status, int mergedCount, String reviewedBy, long reviewedAt, String actions, String previousFlagId, double tps) {
    public static final int MAX_DETAILS_LENGTH = 1500;
    public static final int MAX_REASON_LENGTH = 300;

    public FlagRecord {
        reason = FlagRecord.truncate(reason, 300);
        details = FlagRecord.truncate(details, 1500);
        actions = actions == null ? "" : FlagRecord.truncate(actions, 300);
    }

    public boolean isCurrent(long now, long currentWindowMs) {
        return now - this.createdAt <= currentWindowMs;
    }

    public String shortId() {
        return this.flagId.length() > 8 ? this.flagId.substring(0, 8) : this.flagId;
    }

    public boolean hasPosition() {
        return this.world != null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }
}

