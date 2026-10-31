/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Location
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.checks.movement;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.MoveContext;
import de.kyrosmp.anticheat.core.Physics;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.SetbackService;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.ArrayDeque;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

public final class ElytraCheck {
    public static final int HOVER_TICKS = 15;
    public static final long BOOST_RESET_MS = 3500L;
    private final FlagSink sink;
    private final SetbackService punisher;
    private volatile CheckConfig cfg;
    private PreventionConfig prevention = PreventionConfig.defaults();
    public ElytraCheck withPrevention(PreventionConfig policy) { prevention = policy; return this; }
    public static final long CANDIDATE_CONFIRM_MS = 1000L;
    public static final long RECENT_GROUND_MS = 3000L;
    public static final long GLIDE_SAFE_MAX_AGE_MS = 10000L;

    public ElytraCheck(FlagSink sink, SetbackService punisher, CheckConfig cfg) {
        this.sink = sink;
        this.punisher = punisher;
        this.cfg = cfg;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void check(Player p, PlayerData d, MoveContext m) {
        boolean boosted;
        CheckConfig c = this.cfg;
        if (!c.elytraEnabled() || !m.gliding || m.gameMode == GameMode.CREATIVE || m.gameMode == GameMode.SPECTATOR || m.allowFlight || m.flying) {
            ElytraCheck.reset(d);
            d.elytraBuffer = Math.max(0.0, d.elytraBuffer - 0.1);
            return;
        }
        long now = m.now;
        boolean bl = boosted = now - d.lastElytraBoost < 3500L || now < d.velocityGraceUntil || m.riptiding || now - d.lastRiptide < c.graceRiptideMs() || now - d.lastTeleport < c.graceTeleportMs() || m.insideVehicle || m.levitation || now - d.lastLiquid < 600L;
        if (boosted) {
            ElytraCheck.reset(d);
            return;
        }
        double speed = m.speed3d();
        String mode = null;
        boolean still = m.horizontal < 0.08 && Math.abs(m.dy) < 0.03;
        int n = d.elytraHoverTicks = still && !m.geo.collisionNearby() ? d.elytraHoverTicks + 1 : 0;
        if (d.elytraHoverTicks == 0) d.elytraHoverSince=0;
        else if (d.elytraHoverSince == 0) d.elytraHoverSince=now;
        if (d.elytraHoverTicks >= 15 && now-d.elytraHoverSince>=700) {
            mode = "hover";
        }
        double ceiling = m.toY + Physics.elytraClimbBudget(speed);
        double min = d.elytraCeilingMin;
        if (Double.isNaN(min)) {
            min = m.fromY + Physics.elytraClimbBudget(speed);
        }
        if (m.toY > min && mode == null) {
            mode = String.format(Locale.US, "climb: y=%.1f above ceiling %.1f", m.toY, min);
        }
        d.elytraCeilingMin = Math.min(min, ceiling);
        int n2 = d.elytraFastTicks = speed > c.elytraMaxSpeed() ? d.elytraFastTicks + 1 : 0;
        if (d.elytraFastTicks >= 3 && mode == null) {
            mode = String.format(Locale.US, "speed=%.2f > %.2f", speed, c.elytraMaxSpeed());
        }
        if (mode == null) {
            d.elytraBuffer = Math.max(0.0, d.elytraBuffer - 0.1);
            if (d.elytraBuffer == 0.0 && m.world != null) {
                if (d.elytraCandAt != 0L && d.elytraCandWorld == m.world && now - d.elytraCandAt >= 1000L) {
                    d.elytraSafeWorld = d.elytraCandWorld;
                    d.elytraSafeX = d.elytraCandX;
                    d.elytraSafeY = d.elytraCandY;
                    d.elytraSafeZ = d.elytraCandZ;
                    d.elytraSafeAt = d.elytraCandAt;
                    d.elytraCandAt = 0L;
                }
                if (d.elytraCandAt == 0L) {
                    d.elytraCandWorld = m.world;
                    d.elytraCandX = m.toX;
                    d.elytraCandY = m.toY;
                    d.elytraCandZ = m.toZ;
                    d.elytraCandAt = now;
                }
            }
            return;
        }
        d.elytraCandAt = 0L;
        if (prevention.instant() && c.elytraSetback()) {
            d.preventMove = true;
            Location safe = verifiedGlidePosition(d, m, now);
            if (safe != null) this.punisher.setbackTo(p, d, safe, null);
            else this.punisher.setback(p, d, null);
        }
        d.elytraBuffer += 1.0;
        if (d.elytraBuffer >= c.elytraFlagAt()) {
            String evidenceId;
            String reason = mode.startsWith("hover") ? "Hovering while gliding" : (mode.startsWith("climb") ? "Gaining more altitude than the glide allows" : "Gliding faster than possible without boost");
            int incidents = ElytraCheck.recordIncident(d, now, c.elytraSetbackWindowMs());
            String id = this.sink.flag(p, d, "ElytraFly A", reason, String.format(Locale.US, "mode=%s; dy=%.3f; horizontal=%.3f; speed=%.3f; buffer=%.1f; incidents=%d/%ds", mode, m.dy, m.horizontal, speed, d.elytraBuffer, incidents, c.elytraSetbackWindowMs() / 1000L));
            d.elytraBuffer = 1.0;
            if (id != null) {
                d.lastElytraFlagId = id;
            }
            String string = evidenceId = id != null ? id : d.lastElytraFlagId;
            if (prevention.movement() && c.elytraSetback() && incidents >= c.elytraSetbackAfterFlags()) {
                boolean done;
                boolean recentGround = d.lastSafe != null && d.lastSafe.getWorld() == m.world && now - d.lastSafeAt <= 3000L;
                Location target = recentGround ? null : ElytraCheck.verifiedGlidePosition(d, m, now);
                boolean bl2 = done = target != null ? this.punisher.setbackTo(p, d, target, evidenceId) : this.punisher.setback(p, d, evidenceId);
                if (done) {
                    ArrayDeque<Long> arrayDeque = d.elytraIncidents;
                    synchronized (arrayDeque) {
                        d.elytraIncidents.clear();
                    }
                    if (evidenceId != null) {
                        this.sink.action(evidenceId, "Setback (" + incidents + " incidents)");
                    }
                } else if (target != null) {
                    d.elytraSafeAt = 0L;
                }
            }
        }
    }

    static Location verifiedGlidePosition(PlayerData d, MoveContext m, long now) {
        if (d.elytraSafeAt == 0L || now - d.elytraSafeAt > 10000L) {
            return null;
        }
        if (d.elytraSafeWorld == null || d.elytraSafeWorld != m.world) {
            return null;
        }
        return new Location(d.elytraSafeWorld, d.elytraSafeX, d.elytraSafeY, d.elytraSafeZ);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    static int recordIncident(PlayerData d, long now, long windowMs) {
        ArrayDeque<Long> arrayDeque = d.elytraIncidents;
        synchronized (arrayDeque) {
            d.elytraIncidents.addLast(now);
            while (!(d.elytraIncidents.isEmpty() || now - d.elytraIncidents.peekFirst() <= windowMs && d.elytraIncidents.size() <= 16)) {
                d.elytraIncidents.pollFirst();
            }
            return d.elytraIncidents.size();
        }
    }

    private static void reset(PlayerData d) {
        d.elytraCeilingMin = Double.NaN;
        d.elytraHoverTicks = 0;
        d.elytraHoverSince = 0;
        d.elytraFastTicks = 0;
        // Confirmation requires an uninterrupted interval of validated glide moves.
        d.elytraCandAt = 0L;
        d.elytraCandWorld = null;
    }
}

