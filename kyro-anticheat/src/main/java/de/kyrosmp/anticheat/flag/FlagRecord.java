package de.kyrosmp.anticheat.flag;

import de.kyrosmp.anticheat.core.CheckCategory;

import java.util.UUID;

public record FlagRecord(
        String flagId,
        UUID playerUuid,
        String playerName,
        CheckCategory category,
        String checkName,
        double vl,
        long createdAt,
        String reason,
        String details,
        int ping,
        Confidence confidence,
        String world,
        double x,
        double y,
        double z,
        FlagStatus status,
        int mergedCount,
        String reviewedBy,
        long reviewedAt,
        String actions,
        String previousFlagId,
        double tps) {
    public static final int MAX_DETAILS_LENGTH = 1500;
    public static final int MAX_REASON_LENGTH = 300;

    public FlagRecord(
            String flagId,
            UUID playerUuid,
            String playerName,
            CheckCategory category,
            String checkName,
            double vl,
            long createdAt,
            String reason,
            String details,
            int ping,
            Confidence confidence,
            String world,
            double x,
            double y,
            double z,
            FlagStatus status,
            int mergedCount,
            String reviewedBy,
            long reviewedAt,
            String actions,
            String previousFlagId,
            double tps) {
        reason = truncate(reason, 300);
        details = truncate(details, 1500);
        actions = actions == null ? "" : truncate(actions, 300);
        this.flagId = flagId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.category = category;
        this.checkName = checkName;
        this.vl = vl;
        this.createdAt = createdAt;
        this.reason = reason;
        this.details = details;
        this.ping = ping;
        this.confidence = confidence;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.status = status;
        this.mergedCount = mergedCount;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.actions = actions;
        this.previousFlagId = previousFlagId;
        this.tps = tps;
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
        } else {
            return s.length() <= max ? s : s.substring(0, max - 1) + "…";
        }
    }
}
