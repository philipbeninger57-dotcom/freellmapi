/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.GameMode
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
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.Locale;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class MovementChecks {
    private final FlagSink sink;
    private final SetbackService punisher;
    private volatile CheckConfig cfg;
    private PreventionConfig prevention = PreventionConfig.defaults();
    public MovementChecks withPrevention(PreventionConfig policy) { this.prevention = policy; return this; }
    private volatile StrictConfig strict = StrictConfig.defaults();
    /** 3.7.2 switches (opt-in Timer blocking). */
    public MovementChecks withStrict(StrictConfig strict) { this.strict = strict; return this; }
    public boolean preventionEnabled() { return prevention.movement(); }
    public void rememberSafe(PlayerData d, MoveContext m, boolean ground, boolean exempt) {
        updateSafePosition(d,m,ground,exempt);
        if (!prevention.safePositionHistory()) d.safePositions.clear();
    }

    private void prevent(Player p, PlayerData d, MoveContext m) {
        if (!prevention.movement()) return;
        d.preventMove = true;
        this.punisher.setback(p, d, null);
    }
    public static final int JESUS_SUSTAINED_MOVES = 20;
    static final long STATIONARY_HOVER_MS = 1500L;
    static final long HOVER_SAMPLE_MAX_GAP_MS = 250L;

    public MovementChecks(FlagSink sink, SetbackService punisher, CheckConfig cfg) {
        this.sink = sink;
        this.punisher = punisher;
        this.cfg = cfg;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public boolean checkSpeed(Player p, PlayerData d, MoveContext m, boolean ground) {
        boolean extreme;
        double horizontal;
        int ping;
        double attr;
        CheckConfig c = this.cfg;
        if (!c.speedEnabled()) {
            return false;
        }
        long now = m.now;
        if (now - d.lastLiquid < 800L) {
            d.speedBuffer = Math.max(0.0, d.speedBuffer - 1.0);
            return false;
        }
        boolean sprint = m.sprinting;
        double limit = sprint ? 0.48 : 0.38;
        double mult = 1.0;
        double walk = m.walkSpeed;
        if (walk > 0.2) {
            mult = Math.max(mult, walk / 0.2);
        }
        if (!Double.isNaN(attr = m.movementSpeedAttr) && attr > 0.0) {
            double base = sprint ? 0.13 : 0.1;
            mult = Math.max(mult, 1.0 + Math.max(0.0, attr / base - 1.0) * 1.15);
        } else if (m.speedAmplifier >= 0) {
            mult = Math.max(mult, 1.0 + 0.28 * (double)(m.speedAmplifier + 1));
        }
        limit *= mult;
        double dy = m.dy;
        if (!ground) {
            limit += 0.12;
        }
        if (d.lastGround && !ground && dy > 0.0) {
            limit += 0.12;
        }
        if (m.geo.ceiling()) {
            limit += 0.35;
        }
        if (now - d.lastSlipperySurface < 1500L) {
            limit += 0.65;
        }
        if (now - d.lastBounceSurface < 1500L) {
            limit += 0.35;
        }
        if ((ping = m.ping) > 100) {
            limit += 0.06;
        }
        if (ping > 180) {
            limit += 0.1;
        }
        d.speedBuffer = (horizontal = m.horizontal) > limit ? d.speedBuffer + (horizontal > limit + 0.25 ? 1.0 : 0.5) : Math.max(0.0, d.speedBuffer - 0.45);
        boolean bl = extreme = horizontal > limit + 1.0;
        boolean preventSpeed = prevention.instant() && horizontal > limit + 0.1;
        if (preventSpeed && c.speedSetback()) prevent(p, d, m);
        if (extreme || d.speedBuffer >= c.speedFlagAt()) {
            String id = this.sink.flag(p, d, "Speed A", "Horizontal speed above the calculated limit", String.format(Locale.US, "horizontal=%.3f; limit=%.3f; buffer=%.2f; sprint=%s; ping=%d", horizontal, limit, d.speedBuffer, sprint, ping));
            d.speedBuffer = 1.5;
            boolean cancel = prevention.movement() && c.speedSetback();
            if (cancel && id != null) {
                this.sink.action(id, "Movement reverted");
            }
            return cancel;
        }
        return preventSpeed && c.speedSetback();
    }

    public void checkFly(Player p, PlayerData d, MoveContext m, boolean ground) {
        CheckConfig c = this.cfg;
        if (!c.flyEnabled()) {
            resetUnsupportedHover(d);
            return;
        }
        if (ground) {
            resetUnsupportedHover(d);
            d.airTicks = 0;
            d.airSince = 0;
            d.hoverTicks = 0;
            d.groundY = m.toY;
            d.flyBuffer = Math.max(0.0, d.flyBuffer - 0.8);
            d.groundSinceFlyIncident = true;
            d.graceFlyEvidence = false;
            return;
        }
        long now = m.now;
        double dy = m.dy;
        double horizontal = m.horizontal;
        if (d.airSince == 0) d.airSince = now;
        ++d.airTicks;
        double g = Physics.gravity(m.gravityAttr);
        double v0 = Physics.jumpVelocity(m.jumpStrengthAttr, m.jumpAmplifier);
        int riseLimit = Math.max(8, Physics.riseTicks(v0, g) + 3);
        boolean vanillaGravity = g >= 0.03;
        String mode = null;
        double height = m.toY - d.groundY;
        double maxHeight = Physics.maxJumpHeight(v0, g) + 0.6;
        boolean stationaryHover = sustainedUnsupportedHover(d, m, Math.max(STATIONARY_HOVER_MS, (long)(c.flyFlagAt() * 200.0)));
        if (stationaryHover && !(Math.abs(dy) < 0.008 && horizontal > 0.045)) {
            this.flagStationaryHover(p, d, m);
        }
        if (height > maxHeight && dy > -0.05 && now - d.lastLiquid > 2500L) {
            d.flyBuffer += 2.0;
            mode = String.format(Locale.US, "height=%.2f max=%.2f", height, maxHeight);
        } else if (d.airTicks > riseLimit && now - d.airSince >= 500L) {
            boolean ascending = dy > 0.145;
            boolean hover = vanillaGravity && Math.abs(dy) < 0.008 && horizontal > 0.045;
            boolean glide = vanillaGravity && d.airTicks >= 15 && dy > -0.02 && horizontal > 0.025;
            int n = d.hoverTicks = hover ? d.hoverTicks + 1 : 0;
            if (ascending) {
                d.flyBuffer += 1.0;
                mode = "ascending";
            } else if (d.hoverTicks >= 3) {
                d.flyBuffer += 0.72;
                mode = "hover";
            } else if (glide) {
                d.flyBuffer += 0.4;
                mode = "glide";
            } else {
                d.flyBuffer = Math.max(0.0, d.flyBuffer - 0.35);
            }
        } else {
            d.flyBuffer = Math.max(0.0, d.flyBuffer - 0.1);
        }
        if (mode != null && c.flySetback() && prevention.instant()) prevent(p, d, m);
        this.flagFlyIfDue(p, d, m, mode);
    }

    public void checkFlyDuringGrace(Player p, PlayerData d, MoveContext m, boolean ground, String graceReason) {
        int impulseRise;
        long ticksSinceImpulse;
        CheckConfig c = this.cfg;
        if (!c.flyEnabled()) {
            resetUnsupportedHover(d);
            return;
        }
        if (ground || m.geo.ceiling() || m.geo.liquidOrClimbable()) {
            resetUnsupportedHover(d);
            d.airTicks = 0;
            d.airSince = 0;
            d.hoverTicks = 0;
            d.graceFlyEvidence = false;
            if (ground) {
                d.groundSinceFlyIncident = true;
            }
            return;
        }
        if (d.airSince == 0) d.airSince = m.now;
        ++d.airTicks;
        double g = Physics.gravity(m.gravityAttr);
        if (g < 0.03) {
            resetUnsupportedHover(d);
            return;
        }
        int pingTicks = Math.min(20, m.ping / 50);
        int jumpRise = Physics.riseTicks(Physics.jumpVelocity(m.jumpStrengthAttr, m.jumpAmplifier), g) + 4 + pingTicks;
        boolean ascendingTooLong = !Double.isNaN(d.impulseVy) && d.impulseAt > 0L && m.now - d.impulseAt < 10000L ? (ticksSinceImpulse = (m.now - d.impulseAt) / 50L) > (long)(impulseRise = Physics.riseTicks(Math.max(0.0, d.impulseVy), g) + 4 + pingTicks) && d.airTicks > jumpRise : d.airTicks > jumpRise;
        String mode = null;
        boolean stationaryHover = sustainedUnsupportedHover(d, m, Math.max(STATIONARY_HOVER_MS, (long)(c.flyFlagAt() * 200.0)));
        if (stationaryHover && !(Math.abs(m.dy) < 0.008 && m.horizontal > 0.045)) {
            this.flagStationaryHover(p, d, m);
        }
        boolean hover = Math.abs(m.dy) < 0.008 && m.horizontal > 0.045;
        int n = d.hoverTicks = hover ? d.hoverTicks + 1 : 0;
        if (ascendingTooLong && m.now - d.airSince >= 500L && m.dy > 0.145) {
            d.flyBuffer += 1.0;
            d.graceFlyEvidence = true;
            mode = "ascending during " + graceReason.toLowerCase(Locale.ROOT) + " grace";
        } else if (d.hoverTicks >= 4 && m.now - d.airSince >= 500L) {
            d.flyBuffer += 0.72;
            d.graceFlyEvidence = true;
            mode = "hover during " + graceReason.toLowerCase(Locale.ROOT) + " grace";
        } else {
            d.flyBuffer = Math.max(0.0, d.flyBuffer - 0.35);
        }
        if (mode != null && c.flySetback() && prevention.instant()) prevent(p, d, m);
        this.flagFlyIfDue(p, d, m, mode);
    }

    private static void resetUnsupportedHover(PlayerData d) {
        d.unsupportedHoverSince = 0L;
        d.unsupportedHoverLastAt = 0L;
        d.unsupportedHoverY = Double.NaN;
    }

    private static boolean sustainedUnsupportedHover(PlayerData d, MoveContext m, long requiredDuration) {
        // Require real position samples over time. Rotation frequency and a short
        // jump apex are not evidence of a sustained unsupported hover.
        if (m.total <= 1.0E-4 || Physics.gravity(m.gravityAttr) < 0.03
                || m.geo.nearGround() || m.geo.collisionNearby() || m.geo.ceiling()
                || m.geo.liquidOrClimbable()) {
            resetUnsupportedHover(d);
            return false;
        }
        if (d.unsupportedHoverLastAt == 0L || m.now < d.unsupportedHoverLastAt
                || m.now - d.unsupportedHoverLastAt > HOVER_SAMPLE_MAX_GAP_MS
                || !Double.isFinite(d.unsupportedHoverY)
                || Math.abs(m.toY - d.unsupportedHoverY) > 0.15) {
            d.unsupportedHoverSince = m.now;
            d.unsupportedHoverY = m.toY;
        }
        d.unsupportedHoverLastAt = m.now;
        return m.now - d.unsupportedHoverSince >= requiredDuration;
    }

    private void flagStationaryHover(Player p, PlayerData d, MoveContext m) {
        if (cfg.flySetback() && prevention.movement()) prevent(p, d, m);
        // Keep this new duration-based evidence separate from the existing Fly A
        // buffer: it must not contribute automatic kick incidents before live
        // server validation. The configured setback can still restore support.
        String id = this.sink.flag(p, d, "Fly A", "Remaining at nearly constant altitude without support",
                String.format(Locale.US, "mode=stationary hover; duration_ms=%d; vertical_band=0.15; dy=%.4f; horizontal=%.3f",
                        m.now - d.unsupportedHoverSince, m.dy, m.horizontal));
        if (prevention.movement() && this.cfg.flySetback() && this.punisher.setback(p, d, id) && id != null) {
            this.sink.action(id, "Setback (sustained unsupported hover)");
        }
        d.unsupportedHoverSince = m.now;
        d.unsupportedHoverY = m.toY;
    }

    private void flagFlyIfDue(Player p, PlayerData d, MoveContext m, String mode) {
        CheckConfig c = this.cfg;
        if (d.flyBuffer < c.flyFlagAt()) {
            return;
        }
        String details = String.format(Locale.US, "mode=%s; air=%d; dy=%.4f; horizontal=%.3f; buffer=%.2f", mode == null ? "air" : mode, d.airTicks, m.dy, m.horizontal, d.flyBuffer);
        String id = this.sink.flag(p, d, "Fly A", MovementChecks.flyReason(mode), details);
        if (prevention.movement() && c.flySetback() && this.punisher.setback(p, d, id)) {
            if (id != null) {
                this.sink.action(id, "Setback");
            }
            d.airTicks = 0;
            Location safe = d.lastSafe;
            if (safe != null) {
                d.groundY = safe.getY();
            }
        }
        if (id != null) {
            this.punisher.registerFlyPunishment(p, d, m.now, id);
        }
        d.flyBuffer = 0.8;
        d.hoverTicks = 0;
    }

    private static String flyReason(String mode) {
        if (mode == null) {
            return "In the air too long without matching fall motion";
        }
        if (mode.startsWith("height")) {
            return "Higher than the maximum possible jump height";
        }
        if (mode.startsWith("ascending during")) {
            return "Keeps rising long after the last impulse";
        }
        if (mode.startsWith("hover during")) {
            return "Hovering in the air after an impulse";
        }
        return switch (mode) {
            case "ascending" -> "Keeps rising after the jump phase";
            case "hover" -> "Hovering at constant height while moving horizontally";
            case "glide" -> "Descending too slowly";
            default -> "Unusual air movement";
        };
    }

    public void checkTimer(Player p, PlayerData d, MoveContext m) {
        CheckConfig c = this.cfg;
        long now = m.now;
        long nanoNow = m.nanoNow;
        // Rotation-only events neither represent movement ticks nor reset their
        // timing reference. Tiny real position changes still consume client ticks.
        if (m.total <= 1.0E-4) {
            return;
        }
        if (c.timerEnabled() && d.lastMoveNanos != 0L) {
            double elapsed = (double)(nanoNow - d.lastMoveNanos) / 1000000.0;
            d.timerBalance = Physics.timerBalance(d.timerBalance, elapsed);
            if (elapsed > 300.0) {
                d.timerGraceUntil = now + 1500L;
            }
            if (now < d.timerGraceUntil) {
                d.timerBalance = Math.min(d.timerBalance, 0.0);
            } else if (d.timerBalance > c.timerMaxBalanceMs()) {
                // Arrival timing alone is weak evidence (network bursts), so Timer is alert-only by
                // default, independent of instant prevention. checks.timer.prevent: true opts into
                // rejecting the surplus moves: no ground setback, and the debt is kept until real
                // elapsed time pays it back, so a faster game clock gains no distance.
                boolean block = this.strict.timerPrevent() && prevention.movement();
                String id = this.sink.flag(p, d, "Timer A", "Movement event rate above the expected 20 ticks per second", String.format(Locale.US, "balance=+%.0fms; limit=%.0fms", d.timerBalance, c.timerMaxBalanceMs()));
                if (block) {
                    d.preventMove = true;
                    if (id != null) this.sink.action(id, "Move rejected (timer)");
                } else {
                    d.timerBalance = 0.0;
                }
            }
        }
        d.lastMoveNanos = nanoNow;
    }

    public void checkNoFall(Player p, PlayerData d, MoveContext m, boolean ground, boolean exempt) {
        CheckConfig c = this.cfg;
        long now = m.now;
        double y = m.toY;
        if (exempt || now - d.lastLiquid < 500L) {
            d.fallSafe = null;
            d.fallPeakY = y;
            d.landedAt = 0L;
            d.groundSpoofBuffer = 0.0;
            if (ground) {
                d.impulseY = Double.NaN;
            }
        } else if (!ground) {
            if (d.lastGround && d.lastSafe != null) d.fallSafe = d.lastSafe.clone();
            d.fallPeakY = Math.max(d.fallPeakY, y);
        } else {
            if (!d.lastGround) {
                boolean damageDisabled;
                double fell = d.fallPeakY - y;
                if (!Double.isNaN(d.impulseY)) {
                    fell = Math.min(fell, d.impulseY - y);
                }
                double threshold = Physics.noFallThreshold(m.jumpAmplifier, m.safeFallAttr);
                boolean bl = damageDisabled = !Double.isNaN(m.fallDamageMultiplierAttr) && m.fallDamageMultiplierAttr <= 0.0;
                if (fell > threshold && !damageDisabled && !m.geo.safeNoDamageLanding() && m.fallDamageEnabled) {
                    d.landedAt = now;
                    d.landedFallDistance = fell;
                }
            }
            d.fallPeakY = y;
            d.impulseY = Double.NaN;
        }
        if (d.landedAt > 0L && now - d.landedAt > 750L) {
            // Merely holding a mace cannot cancel vanilla fall damage. Only a
            // server-confirmed smash near this landing explains its absence.
            boolean maceSmash = d.lastMaceSmashAt > 0L && d.lastMaceSmashAt >= d.landedAt - 1000L
                    && d.lastMaceSmashAt <= now;
            if (!maceSmash && c.noFallEnabled() && d.lastFallDamage < d.landedAt - 250L && now - d.lastTeleport > c.graceTeleportMs() && now - d.lastVelocity > c.graceVelocityMs() && d.landedAt - d.lastDamage > 1000L && !m.invulnerable) {
                if (prevention.instant() && c.groundSpoofSetback()) {
                    d.preventMove=true;
                    if (d.fallSafe != null) this.punisher.setbackTo(p,d,d.fallSafe,null);
                    else this.punisher.setback(p,d,null);
                }
                this.sink.flag(p, d, "NoFall A", "Fall without fall damage", String.format(Locale.US, "fell=%.2f blocks without damage; threshold=%.1f", d.landedFallDistance, Physics.noFallThreshold(m.jumpAmplifier, m.safeFallAttr)));
            }
            d.landedAt = 0L;
            d.landedFallDistance = 0.0;
            d.fallSafe = null;
        }
        if (!exempt && m.clientOnGround && now - d.lastGhostBlock > 1500L && !m.geo.nearGround()) {
            d.groundSpoofBuffer += 1.0;
            if (d.groundSpoofBuffer >= 4.0) {
                if (c.noFallEnabled()) {
                    if (prevention.movement() && c.groundSpoofSetback()) prevent(p, d, m);
                    String id = this.sink.flag(p, d, "NoFall B", "Client claims ground although no ground is nearby", String.format(Locale.US, "ground spoof; client_on_ground=true; buffer=%.1f", d.groundSpoofBuffer));
                    if (prevention.movement() && c.groundSpoofSetback() && this.punisher.setback(p, d, id) && id != null) {
                        this.sink.action(id, "Setback");
                    }
                }
                d.groundSpoofBuffer = 1.0;
            }
        } else {
            d.groundSpoofBuffer = Math.max(0.0, d.groundSpoofBuffer - 0.5);
        }
    }

    public static boolean invalidMovementPacket(Location from, Location to) {
        if (from == null || to == null) {
            return false;
        }
        return !Double.isFinite(to.getX()) || !Double.isFinite(to.getY()) || !Double.isFinite(to.getZ()) || !Float.isFinite(to.getYaw()) || !Float.isFinite(to.getPitch()) || Math.abs(to.getPitch()) > 90.0001f;
    }

    public void checkJesus(Player p, PlayerData d, MoveContext m, boolean ground) {
        boolean slowDescent;
        CheckConfig c = this.cfg;
        if (!c.jesusEnabled()) {
            return;
        }
        long now = m.now;
        GameMode gm = m.gameMode;
        boolean bl = slowDescent = m.slowFalling || !Double.isNaN(m.gravityAttr) && m.gravityAttr < 0.06;
        if (ground || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || m.allowFlight || m.flying || m.gliding || m.insideVehicle || m.swimming || m.riptiding || slowDescent || now - d.lastVehicle < 1500L || now < d.velocityGraceUntil || now - d.lastTeleport < c.graceTeleportMs() || now - d.lastBounceSurface < 1500L || m.levitation) {
            d.jesusTicks = 0;
            d.dryOverLiquidTicks = 0;
            d.jesusSince = 0;
            d.jesusLastAt = 0;
            d.jesusBuffer = Math.max(0.0, d.jesusBuffer - 0.05);
            return;
        }
        boolean dryOverLiquid = m.geo.liquidUnderFeet() && !m.geo.bodyWet();
        if (!dryOverLiquid || (d.jesusLastAt != 0 && (now < d.jesusLastAt || now - d.jesusLastAt > 250))) {
            d.jesusSince = 0;
            d.jesusTicks = 0;
            d.dryOverLiquidTicks = 0;
        }
        if (dryOverLiquid) {
            if (d.jesusSince == 0) d.jesusSince = now;
            d.jesusLastAt = now;
        } else d.jesusLastAt = 0;
        String mode = null;
        if (dryOverLiquid && Math.abs(m.dy) < 0.02 && m.total > 0.0001) {
            ++d.jesusTicks;
            if (d.jesusTicks >= c.jesusTicks() && now - d.jesusSince >= 350) {
                d.jesusTicks = 0;
                mode = "level";
            }
        } else {
            d.jesusTicks = 0;
        }
        if (dryOverLiquid && m.total > 0.0001) {
            if (++d.dryOverLiquidTicks >= 20 && now - d.jesusSince >= 950) {
                d.dryOverLiquidTicks = 0;
                if (mode == null) {
                    mode = "sustained";
                }
            }
        } else {
            d.dryOverLiquidTicks = 0;
        }
        if (mode == null) {
            if (!dryOverLiquid) {
                d.jesusBuffer = Math.max(0.0, d.jesusBuffer - 0.02);
            }
            return;
        }
        d.jesusBuffer += 1.0;
        // 3.6 instant policy intentionally activates prevention for older configs
        // whose Jesus check previously had setback:false. Disable instant policy
        // to restore the older per-check alert-only setting.
        if (prevention.instant() || (prevention.movement() && c.jesusSetback())) prevent(p, d, m);
        if (d.jesusBuffer >= c.jesusFlagAt()) {
            String id = this.sink.flag(p, d, "Jesus A", "Walking on liquid without sinking", String.format(Locale.US, "mode=%s; dy=%.4f; horizontal=%.3f; buffer=%.1f", mode, m.dy, m.horizontal, d.jesusBuffer));
            if (prevention.movement() && c.jesusSetback() && this.punisher.setback(p, d, id) && id != null) {
                this.sink.action(id, "Setback");
            }
            d.jesusBuffer = 1.0;
        }
    }

    public static void updateSafePosition(PlayerData d, MoveContext m, boolean ground, boolean exempt) {
        if (ground && !exempt && m.now - d.lastLiquid > 600L) {
            d.lastSafe = new Location(m.world, m.toX, m.toY, m.toZ, m.yaw, m.pitch);
            d.lastSafeAt = m.now;
            d.safePositions.addLast(d.lastSafe);
            while (d.safePositions.size() > 20) d.safePositions.removeFirst();
        }
    }

    public static void rememberMove(PlayerData d, double y, boolean ground, long now) {
        d.lastY = y;
        d.lastGround = ground;
        d.lastMove = now;
    }
}

