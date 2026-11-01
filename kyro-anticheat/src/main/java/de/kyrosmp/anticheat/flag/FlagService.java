package de.kyrosmp.anticheat.flag;

import de.kyrosmp.anticheat.core.AlertManager;
import de.kyrosmp.anticheat.core.CheckInfo;
import de.kyrosmp.anticheat.core.CheckRegistry;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerHistory;
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.core.ServerStats;
import de.kyrosmp.anticheat.core.ViolationLevels;
import de.kyrosmp.anticheat.debug.DebugManager;
import de.kyrosmp.anticheat.storage.FlagStore;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FlagService implements FlagSink {
    private final CheckRegistry registry;
    private final ViolationLevels levels;
    private final FlagThrottle throttle;
    private final RecentFlagIndex recent;
    private final FlagStore store;
    private final AlertManager alerts;
    private final DebugManager debug;
    private final Map<UUID, FlagService.LastFlag> lastFlag = new ConcurrentHashMap<>();
    private volatile FlagService.Settings settings;

    public FlagService(
            CheckRegistry registry,
            ViolationLevels levels,
            FlagThrottle throttle,
            RecentFlagIndex recent,
            FlagStore store,
            AlertManager alerts,
            DebugManager debug,
            FlagService.Settings settings) {
        this.registry = registry;
        this.levels = levels;
        this.throttle = throttle;
        this.recent = recent;
        this.store = store;
        this.alerts = alerts;
        this.debug = debug;
        this.settings = settings;
    }

    public void reconfigure(FlagService.Settings settings) {
        this.settings = settings;
    }

    public FlagService.Settings settings() {
        return this.settings;
    }

    @Override
    public String flag(Player p, PlayerData d, String check, String reason, String details) {
        if (p.hasPermission("kyroac.bypass")) {
            return null;
        } else {
            FlagService.Settings s = this.settings;
            long now = System.currentTimeMillis();
            UUID id = p.getUniqueId();
            CheckInfo info = this.registry.get(check);
            if (this.throttle.isDuplicate(id, check, now)) {
                this.debug.event(id, () -> "§8Duplicate dropped: " + check);
                return null;
            } else {
                double vl = this.levels.add(id, check, s.vlPerFlag(), now);
                RecentFlagIndex.Stats stats =
                        this.recent.addAndStats(
                                id, check, info.correlationGroup(), now, s.confidenceWindowMs());
                int ping = Punisher.safePing(p);
                Location loc = p.getLocation();
                double tps = ServerStats.tps(loc);
                String qualityReason = dataQualityReason(ping, tps, s);
                boolean poorData = !qualityReason.isEmpty();
                Confidence confidence = s.calculator().evaluate(stats, poorData);
                FlagThrottle.Decision decision =
                        this.throttle.register(
                                id, check, now, confidence, UUID.randomUUID().toString());
                if (decision.action() == FlagThrottle.Action.MERGE) {
                    this.store.merge(decision.flagId(), vl, decision.confidence());
                    this.debug.event(
                            id,
                            () ->
                                    String.format(
                                            Locale.US,
                                            "§7%s within cooldown, merged §8(VL %.1f, %s§8)",
                                            check,
                                            vl,
                                            decision.confidence().colored()));
                    return null;
                } else {
                    PlayerHistory.Summary hist = d == null ? null : d.history.summary(now, 1000L);
                    String evidence =
                            details
                                    + "; ping="
                                    + ping
                                    + (Double.isFinite(tps) && tps >= 0.0
                                            ? String.format(Locale.US, "; tps=%.1f", tps)
                                            : "; tps=unavailable")
                                    + (poorData
                                            ? "; data_quality="
                                                    + qualityReason
                                                    + " (confidence limited)"
                                            : "")
                                    + "; repeats="
                                    + stats.repetitions()
                                    + "; groups="
                                    + stats.independentGroups()
                                    + (hist == null ? "" : "; " + hist.compact());
                    FlagService.LastFlag previous =
                            this.lastFlag.put(id, new FlagService.LastFlag(decision.flagId(), now));
                    FlagRecord record =
                            new FlagRecord(
                                    decision.flagId(),
                                    id,
                                    p.getName(),
                                    info.category(),
                                    check,
                                    vl,
                                    now,
                                    reason,
                                    evidence,
                                    ping,
                                    confidence,
                                    loc.getWorld() == null ? null : loc.getWorld().getName(),
                                    loc.getX(),
                                    loc.getY(),
                                    loc.getZ(),
                                    FlagStatus.NEW,
                                    1,
                                    null,
                                    0L,
                                    "",
                                    previous == null ? null : previous.id(),
                                    tps);
                    this.store.insert(record);
                    this.alerts.flagAlert(p.getName(), check, vl, confidence, details, ping);
                    this.debug.event(
                            id,
                            () ->
                                    String.format(
                                            Locale.US,
                                            "§cFLAG %s §8#%s §7VL %.1f %s §8| §7%s",
                                            check,
                                            record.shortId(),
                                            vl,
                                            confidence.colored(),
                                            details));
                    return decision.flagId();
                }
            }
        }
    }

    @Override
    public void action(String flagId, String action) {
        if (flagId != null) {
            this.store.appendAction(flagId, action);
        }
    }

    public void resetCurrent(UUID player) {
        this.levels.reset(player);
        this.recent.reset(player);
        this.clearHistoryReferences(player);
    }

    public void clearHistoryReferences(UUID player) {
        this.throttle.reset(player);
        this.lastFlag.remove(player);
    }

    public void cleanup(long now) {
        this.levels.cleanup(now);
        this.recent.cleanup(now, this.settings.confidenceWindowMs());
        this.throttle.cleanup(now, 600000L);
        this.lastFlag.values().removeIf(l -> now - l.time() > 3600000L);
    }

    static String dataQualityReason(int ping, double tps, FlagService.Settings settings) {
        String reason =
                ping >= 0 && ping <= settings.poorDataPing() ? "" : "high or unavailable ping";
        if (settings.poorDataMinTps() > 0.0) {
            String tpsReason =
                    Double.isFinite(tps) && !(tps < 0.0)
                            ? (tps < settings.poorDataMinTps() ? "low TPS" : "")
                            : "TPS unavailable";
            if (!tpsReason.isEmpty()) {
                reason = reason.isEmpty() ? tpsReason : reason + ", " + tpsReason;
            }
        }

        return reason;
    }

    private static record LastFlag(String id, long time) {}

    public static record Settings(
            double vlPerFlag,
            long confidenceWindowMs,
            int poorDataPing,
            long currentWindowMs,
            ConfidenceCalculator calculator,
            double poorDataMinTps) {
        public Settings(
                double vlPerFlag,
                long confidenceWindowMs,
                int poorDataPing,
                long currentWindowMs,
                ConfidenceCalculator calculator,
                double poorDataMinTps) {
            poorDataMinTps =
                    Double.isFinite(poorDataMinTps)
                            ? Math.max(0.0, Math.min(20.0, poorDataMinTps))
                            : 18.0;
            this.vlPerFlag = vlPerFlag;
            this.confidenceWindowMs = confidenceWindowMs;
            this.poorDataPing = poorDataPing;
            this.currentWindowMs = currentWindowMs;
            this.calculator = calculator;
            this.poorDataMinTps = poorDataMinTps;
        }

        public Settings(
                double vlPerFlag,
                long confidenceWindowMs,
                int poorDataPing,
                long currentWindowMs,
                ConfidenceCalculator calculator) {
            this(vlPerFlag, confidenceWindowMs, poorDataPing, currentWindowMs, calculator, 18.0);
        }

        public static FlagService.Settings from(FileConfiguration c) {
            return new FlagService.Settings(
                    c.getDouble("violations.vl-per-flag", 1.0),
                    c.getLong("confidence.window-minutes", 10L) * 60000L,
                    c.getInt("confidence.poor-data-ping", 250),
                    c.getLong("flags.current-window-minutes", 30L) * 60000L,
                    new ConfidenceCalculator(
                            c.getInt("confidence.medium-repetitions", 3),
                            c.getInt("confidence.high-repetitions", 8),
                            c.getInt("confidence.high-total-with-three-groups", 6)),
                    c.getDouble("confidence.poor-data-min-tps", 18.0));
        }
    }
}
