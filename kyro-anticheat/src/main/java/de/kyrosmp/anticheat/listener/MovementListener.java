/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.destroystokyo.paper.event.player.PlayerElytraBoostEvent
 *  org.bukkit.Location
 *  org.bukkit.entity.Boat
 *  org.bukkit.entity.Entity
 *  org.bukkit.entity.LivingEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.entity.Vehicle
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.event.block.BlockBreakEvent
 *  org.bukkit.event.block.BlockPlaceEvent
 *  org.bukkit.event.entity.EntityDamageEvent
 *  org.bukkit.event.entity.EntityDamageEvent$DamageCause
 *  org.bukkit.event.player.PlayerChangedWorldEvent
 *  org.bukkit.event.player.PlayerJoinEvent
 *  org.bukkit.event.player.PlayerMoveEvent
 *  org.bukkit.event.player.PlayerQuitEvent
 *  org.bukkit.event.player.PlayerRespawnEvent
 *  org.bukkit.event.player.PlayerTeleportEvent
 *  org.bukkit.event.player.PlayerTeleportEvent$TeleportCause
 *  org.bukkit.event.player.PlayerVelocityEvent
 *  org.bukkit.event.vehicle.VehicleEnterEvent
 *  org.bukkit.event.vehicle.VehicleExitEvent
 *  org.bukkit.event.vehicle.VehicleMoveEvent
 *  org.bukkit.util.Vector
 */
package de.kyrosmp.anticheat.listener;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import de.kyrosmp.anticheat.checks.action.BlockPlaceChecks;
import de.kyrosmp.anticheat.checks.combat.VelocityCheck;
import de.kyrosmp.anticheat.checks.movement.ElytraCheck;
import de.kyrosmp.anticheat.checks.movement.HoverCheck;
import de.kyrosmp.anticheat.checks.movement.MovementChecks;
import de.kyrosmp.anticheat.checks.movement.PredictionChecks;
import de.kyrosmp.anticheat.checks.vehicle.BoatFlyCheck;
import de.kyrosmp.anticheat.core.AttributeSet;
import de.kyrosmp.anticheat.core.BedrockDetector;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.ExemptionManager;
import de.kyrosmp.anticheat.core.MoveContext;
import de.kyrosmp.anticheat.core.MoveContexts;
import de.kyrosmp.anticheat.core.MoveGeometry;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerDataStore;
import de.kyrosmp.anticheat.core.PlayerHistory;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.core.TeleportSync;
import de.kyrosmp.anticheat.debug.DebugManager;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

public final class MovementListener
implements Listener {
    private static final Set<String> AIR_GRACE = Set.of("Teleport", "Velocity/knockback");
    private static final Set<EntityDamageEvent.DamageCause> DISPLACING = Set.of(EntityDamageEvent.DamageCause.ENTITY_ATTACK, EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK, EntityDamageEvent.DamageCause.PROJECTILE, EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, EntityDamageEvent.DamageCause.LIGHTNING, EntityDamageEvent.DamageCause.FALLING_BLOCK, EntityDamageEvent.DamageCause.FLY_INTO_WALL, EntityDamageEvent.DamageCause.SONIC_BOOM, EntityDamageEvent.DamageCause.CUSTOM);
    private final PlayerDataStore store;
    private final ExemptionManager exemptions;
    private final FlagSink sink;
    private final DebugManager debug;
    private final Supplier<Checks> checks;
    private final Supplier<CheckConfig> cfg;
    private final AttributeSet attributes;
    private final ContextFactory contexts;
    private final LongSupplier clock;
    private volatile Supplier<StrictConfig> strict = StrictConfig::defaults;

    /** 3.7 settings (setback protection); defaults apply when never set. */
    public MovementListener withStrict(Supplier<StrictConfig> strict) {
        this.strict = strict;
        return this;
    }

    public MovementListener(PlayerDataStore store, ExemptionManager exemptions, FlagSink sink, DebugManager debug, Supplier<Checks> checks, Supplier<CheckConfig> cfg, AttributeSet attributes) {
        this(store, exemptions, sink, debug, checks, cfg, attributes, MoveContexts::capture, System::currentTimeMillis);
    }

    public MovementListener(PlayerDataStore store, ExemptionManager exemptions, FlagSink sink, DebugManager debug, Supplier<Checks> checks, Supplier<CheckConfig> cfg, AttributeSet attributes, ContextFactory contexts, LongSupplier clock) {
        this.store = store;
        this.exemptions = exemptions;
        this.sink = sink;
        this.debug = debug;
        this.checks = checks;
        this.cfg = cfg;
        this.attributes = attributes;
        this.contexts = contexts;
        this.clock = clock;
    }

    private boolean bedrockExempt(Player p, PlayerData d) {
        return this.cfg.get().bedrockExempt() && BedrockDetector.isBedrock(p, d);
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void onMoveLowest(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        PlayerData d = this.store.getQuiet(e.getPlayer());
        d.lowestMoveEvent = e;
        d.lowestToX = to.getX();
        d.lowestToY = to.getY();
        d.lowestToZ = to.getZ();
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onMove(PlayerMoveEvent e) {
        this.handleMove(e, this.clock.getAsLong(), System.nanoTime());
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void handleMove(PlayerMoveEvent e, long now, long nanoNow) {
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        Player p = e.getPlayer();
        PlayerData d = this.store.get(p);
        Location from = e.getFrom();
        d.preventMove = false;
        // Reject malformed destinations before relocation detection or synchronization can
        // persist them. Even when alerts are disabled, NaN/Infinity must not enter state.
        if (MovementChecks.invalidMovementPacket(from, to)) {
            d.lowestMoveEvent = null;
            if (this.cfg.get().badPacketsEnabled() && !this.exemptions.bypass(p) && !this.bedrockExempt(p, d)) {
                this.sink.flag(p, d, "BadPackets A", "Invalid position or rotation values in movement packet", "invalid movement values/pitch; pitch=" + to.getPitch());
            }
            e.setTo(from);
            e.setCancelled(true);
            return;
        }
        boolean redirected = TeleportSync.redirectedByPlugin(d, e, to.getX(), to.getY(), to.getZ());
        d.lowestMoveEvent = null;
        this.syncFrom(p, d, from, now);
        if (redirected) {
            this.handleRedirect(p, d, from, to, now);
            return;
        }
        if (from.getWorld() != to.getWorld() || TeleportSync.distSq(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ()) > 1.0E-8) {
            d.lastPositionChangeAt = now;
        }
        Location accepted = to;
        long teleportsBefore = d.teleportsApplied;
        try {
            if (this.process(e, p, d, from, to, now, nanoNow)) {
                accepted = from;
                e.setTo(from);
                e.setCancelled(true);
            }
        }
        finally {
            if (d.preventMove) {
                accepted = from;
                e.setTo(from);
                e.setCancelled(true);
            }
            if (d.teleportsApplied == teleportsBefore) {
                d.setSync(accepted, now);
            }
        }
    }

    private void syncFrom(Player p, PlayerData d, Location from, long now) {
        TeleportSync.Kind kind = TeleportSync.classifyFrom(d, from.getWorld(), from.getX(), from.getY(), from.getZ(), now);
        switch (kind) {
            case CONTINUOUS: {
                break;
            }
            case CORRECTION: {
                ++d.corrections;
                d.setSync(from, now);
                // A server position correction restarts the client with zero velocity.
                d.resetPrediction(true);
                break;
            }
            case OWN_SETBACK: {
                d.applyTeleport(from, now, true);
                this.debug.event(p.getUniqueId(), () -> "\u00a7eOwn setback completed (detected from the move start)");
                break;
            }
            case RELOCATION: {
                boolean worldChange;
                ++d.relocations;
                boolean bl = worldChange = from.getWorld() != d.syncWorld;
                if (now - d.lastVehicle < 1500L) {
                    d.applyDismount(from, now);
                } else {
                    d.applyRelocation(from, now);
                }
                if (worldChange) {
                    d.lastBoatSafe = null;
                }
                long graceLeft = Math.max(0L, this.cfg.get().graceTeleportMs() - (now - d.lastTeleport));
                this.debug.event(p.getUniqueId(), () -> "\u00a7eServer relocation detected" + (worldChange ? " (world change)" : "") + " \u2013 teleport grace " + graceLeft + " ms");
            }
        }
    }

    private void handleRedirect(Player p, PlayerData d, Location from, Location to, long now) {
        double distSq;
        ++d.pluginRedirects;
        double d2 = distSq = from.getWorld() == to.getWorld() ? TeleportSync.distSq(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ()) : Double.POSITIVE_INFINITY;
        if (distSq <= 1.0) {
            d.setSync(to, now);
        } else {
            d.applyTeleport(to, now, false);
            this.debug.event(p.getUniqueId(), () -> "\u00a7eDestination changed by another plugin \u2013 treated as teleport");
        }
    }

    private boolean process(PlayerMoveEvent e, Player p, PlayerData d, Location from, Location to, long now, long nanoNow) {
        boolean landed;
        MoveGeometry geo;
        boolean ground;
        if (this.exemptions.bypass(p)) {
            // Unknown motion while unchecked: judge the first move after the bypass ends leniently.
            d.resetPrediction(false);
            return false;
        }
        MovementChecks mc = this.checks.get().movement();
        if (this.bedrockExempt(p, d)) {
            d.coolMovementBuffers();
            d.resetPrediction(false);
            return false;
        }
        if (mc.preventionEnabled() && d.pendingSetback != null && now >= d.pendingSetbackAt
                && now - d.pendingSetbackAt < 1000L) {
            // Rejected: the client restarts at 'from' with zero velocity.
            d.resetPrediction(true);
            return true;
        }
        if (from.getWorld() != to.getWorld()) {
            return false;
        }
        MoveContext m = this.contexts.capture(p, from, to, now, nanoNow, this.attributes);
        if (m.total < 1.0E-4) {
            return false;
        }
        boolean prevGround = d.lastGround;
        if (!d.positionInitialized) {
            d.positionInitialized = true;
            d.groundY = from.getY();
            d.fallPeakY = from.getY();
            d.lastTeleport = now;
        }
        if (m.gliding) {
            d.lastGlide = now;
        }
        if (m.riptiding) {
            d.lastRiptide = now;
        }
        if (m.swimming) {
            d.lastSwimming = now;
        }
        if (m.insideVehicle) {
            d.lastVehicle = now;
        }
        boolean touched = !(ground = (geo = m.geo).ground()) && m.dy <= 1.0E-9 && geo.restingAtPreviousXZ();
        boolean bl = landed = ground || touched;
        if (landed) {
            d.groundSinceFlyIncident = true;
        }
        if (geo.liquidOrClimbable()) {
            d.lastLiquid = now;
        }
        if (geo.bounceSurface()) {
            d.lastBounceSurface = now;
        }
        if (geo.slipperySurface()) {
            d.lastSlipperySurface = now;
        }
        this.checks.get().velocity().onMove(d, m.dx, m.dz, now);
        d.prevMoveHorizontal = d.lastMoveHorizontal;
        d.lastMoveHorizontal = m.horizontal;
        mc.checkJesus(p, d, m, ground);
        this.checks.get().elytra().check(p, d, m);
        String exemptReason = this.exemptions.movementReason(m, d);
        boolean exempt = exemptReason != null;
        this.trackExemption(p, d, exemptReason);
        mc.checkNoFall(p, d, m, landed, exempt);
        boolean reverted = false;
        // Count all actual position events while active, including a speed move
        // that will be reverted below. Rejection must not create a timing bypass.
        if (!exempt) {
            mc.checkTimer(p, d, m);
        }
        if (exempt) {
            if (AIR_GRACE.contains(exemptReason)) {
                d.speedBuffer = Math.max(0.0, d.speedBuffer - 0.8);
                d.timerBalance = Math.min(d.timerBalance, 0.0);
                mc.checkFlyDuringGrace(p, d, m, landed, exemptReason);
            } else {
                d.coolMovementBuffers();
            }
            d.groundY = m.toY;
            MovementChecks.rememberMove(d, m.toY, ground, now);
        } else if (mc.checkSpeed(p, d, m, ground)) {
            e.setTo(from);
            MovementChecks.rememberMove(d, from.getY(), d.lastGround, now);
            reverted = true;
        } else {
            mc.checkFly(p, d, m, landed);
            MovementChecks.rememberMove(d, m.toY, ground, now);
        }
        // 3.7: tick-accurate prediction (Fly B, Step A, Speed B, NoSlow A). It also runs for exempt
        // moves so the carried motion always follows what the client really did.
        PredictionChecks prediction = this.checks.get().prediction();
        if (prediction != null && prediction.evaluate(p, d, m, prevGround, landed, exemptReason, reverted || d.preventMove)) {
            d.preventMove = true;
        }
        d.lastMoveDy = m.dy;
        // A destination becomes a setback anchor only after its movement is accepted.
        reverted |= d.preventMove;
        if (reverted) MovementChecks.rememberMove(d, from.getY(), d.lastGround, now);
        if (!reverted && d.pendingSetback == null) {
            mc.rememberSafe(d, m, ground, exempt);
            d.history.addMove(new PlayerHistory.MoveSample(now, m.toX, m.toY, m.toZ, m.yaw, m.pitch, m.dy, m.horizontal, ground, m.ping));
        }
        this.debug.move(p.getUniqueId(), now, () -> String.format(Locale.US, "dy=%.3f h=%.3f ground=%s air=%d \u00a78| \u00a77buf speed=%.2f fly=%.2f timer=%+.0fms spoof=%.1f jesus=%.1f elytra=%.1f \u00a78| \u00a77%s", m.dy, m.horizontal, ground, d.airTicks, d.speedBuffer, d.flyBuffer, d.timerBalance, d.groundSpoofBuffer, d.jesusBuffer, d.elytraBuffer, exempt ? "\u00a7e" + exemptReason : "\u00a7aactive"));
        return reverted;
    }

    private void trackExemption(Player p, PlayerData d, String reason) {
        String before = d.lastExemptReason;
        d.lastExemptReason = reason;
        if (!this.debug.isWatched(p.getUniqueId())) {
            return;
        }
        if (!Objects.equals(before, reason)) {
            this.debug.event(p.getUniqueId(), () -> reason == null ? "\u00a7aMovement checks active" : "\u00a7eExemption: " + reason);
        }
    }

    /**
     * A move cancelled by any plugin is answered by Paper with a position packet: the client
     * restarts at 'from' with zero velocity. Keep the prediction in sync with that.
     */
    @EventHandler(priority=EventPriority.MONITOR)
    public void onMoveMonitor(PlayerMoveEvent e) {
        if (!e.isCancelled()) {
            return;
        }
        PlayerData d = this.store.peek(e.getPlayer().getUniqueId());
        if (d != null) {
            d.resetPrediction(true);
            // The player tried to move: not a motionless hover (for Fly C).
            d.lastPositionChangeAt = this.clock.getAsLong();
        }
    }

    /**
     * Paper rejects some moves before any PlayerMoveEvent ("moved wrongly", "moved too quickly",
     * clipping, unloaded chunks) and sends the client back with zero velocity. The motion state is
     * unknown afterwards, so the next move is judged with take-off rules.
     */
    @EventHandler(priority=EventPriority.MONITOR)
    public void onFailMove(io.papermc.paper.event.player.PlayerFailMoveEvent e) {
        if (e.isAllowed()) {
            return;
        }
        PlayerData d = this.store.peek(e.getPlayer().getUniqueId());
        if (d != null) {
            d.resetPrediction(false);
            d.lastPositionChangeAt = this.clock.getAsLong();
        }
    }

    /**
     * Other plugins (combat tags, region guards) must not be able to veto an anti-cheat setback to a
     * position the player validly stood on moments ago.
     */
    @EventHandler(priority=EventPriority.HIGHEST)
    public void protectSetback(PlayerTeleportEvent e) {
        if (!e.isCancelled() || e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN || !this.strict.get().protectSetbacks()) {
            return;
        }
        PlayerData d = this.store.peek(e.getPlayer().getUniqueId());
        Location to = e.getTo();
        Location pending = d == null ? null : d.pendingSetback;
        if (pending == null || to == null || to.getWorld() != pending.getWorld() || !d.hasFreshPendingSetback(this.clock.getAsLong())) {
            return;
        }
        if (TeleportSync.distSq(to.getX(), to.getY(), to.getZ(), pending.getX(), pending.getY(), pending.getZ()) <= 1.0E-6) {
            e.setCancelled(false);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onVehicleMove(VehicleMoveEvent e) {
        Vehicle vehicle = e.getVehicle();
        if (!(vehicle instanceof Boat)) {
            return;
        }
        Boat boat = (Boat)vehicle;
        Player driver = null;
        for (Entity passenger : boat.getPassengers()) {
            Player pl;
            if (!(passenger instanceof Player)) continue;
            driver = pl = (Player)passenger;
            break;
        }
        if (driver == null || this.exemptions.bypass(driver)) {
            return;
        }
        PlayerData d = this.store.get(driver);
        if (this.bedrockExempt(driver, d)) {
            return;
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from == null || to == null || from.getWorld() != to.getWorld()) {
            return;
        }
        this.checks.get().boat().check(driver, d, boat, from, to, this.clock.getAsLong());
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onVehicleEnter(VehicleEnterEvent e) {
        Entity entity = e.getEntered();
        if (!(entity instanceof Player)) {
            return;
        }
        Player p = (Player)entity;
        PlayerData d = this.store.get(p);
        d.lastVehicle = this.clock.getAsLong();
        d.resetBoatState(e.getVehicle().getUniqueId());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onVehicleExit(VehicleExitEvent e) {
        LivingEntity exited = e.getExited();
        if (!(exited instanceof Player)) {
            return;
        }
        Player p = (Player)exited;
        PlayerData d = this.store.get(p);
        d.lastVehicle = this.clock.getAsLong();
        d.fallPeakY = d.groundY = p.getLocation().getY();
        d.resetBoatState(null);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onFallDamage(EntityDamageEvent e) {
        Entity entity = e.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }
        Player p = (Player)entity;
        PlayerData d = this.store.get(p);
        long now = this.clock.getAsLong();
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            d.lastFallDamage = now;
            return;
        }
        if (!e.isCancelled() && MovementListener.displacing(e.getCause())) {
            d.lastDamage = now;
        }
    }

    public static boolean displacing(EntityDamageEvent.DamageCause cause) {
        return DISPLACING.contains(cause);
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location to = e.getTo();
        if (!validServerDestination(to)) {
            // Invalid server/plugin relocations must not poison state or grant
            // an exemption. They are not evidence of cheating by the player.
            e.setCancelled(true);
            return;
        }
        PlayerData d = this.store.get(e.getPlayer());
        long now = this.clock.getAsLong();
        TeleportSync.Kind kind = TeleportSync.classifyTeleport(d, to, now);
        switch (kind) {
            case OWN_SETBACK: {
                d.applyTeleport(to, now, true);
                break;
            }
            case CORRECTION: {
                ++d.corrections;
                d.setSync(to, now);
                d.resetPrediction(true);
                return;
            }
            default: {
                if (e.getCause() == PlayerTeleportEvent.TeleportCause.DISMOUNT) {
                    d.applyDismount(to, now);
                    break;
                }
                d.applyTeleport(to, now, false);
            }
        }
        this.checks.get().velocity().cancelPending(d);
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void onVelocityLowest(PlayerVelocityEvent e) {
        PlayerData d = this.store.getQuiet(e.getPlayer());
        d.lowestVelocityEvent = e;
        try {
            d.lowestVelocity = e.getVelocity().clone();
        }
        catch (Throwable ex) {
            d.lowestVelocity = null;
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        PlayerData d = this.store.get(e.getPlayer());
        Vector sent = null;
        try {
            sent = e.getVelocity();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        Vector original = d.lowestVelocityEvent == e ? d.lowestVelocity : null;
        d.lowestVelocityEvent = null;
        boolean modified = original != null && sent != null && original.distanceSquared(sent) > 1.0E-8;
        this.checks.get().velocity().onVelocityEvent(d, sent, e.isCancelled(), modified, this.clock.getAsLong());
        if (e.isCancelled()) {
            return;
        }
        double strength = 0.0;
        double vy = 0.0;
        try {
            Vector v = e.getVelocity();
            strength = v.length();
            vy = v.getY();
        }
        catch (Throwable v) {
            // empty catch block
        }
        long now = this.clock.getAsLong();
        MovementListener.applyVelocityGrace(d, strength, now, this.cfg.get());
        MovementListener.registerImpulse(d, vy, now);
        if (sent != null) {
            d.registerPredictionImpulse(sent.getX(), sent.getY(), sent.getZ(), now);
        }
    }

    public static void applyVelocityGrace(PlayerData d, double strength, long now, CheckConfig c) {
        d.lastVelocity = now;
        d.velocityGraceUntil = Math.max(d.velocityGraceUntil, now + c.graceVelocityMs() + (long)Math.min(4500.0, strength * 2500.0));
        if (!d.graceFlyEvidence) {
            d.flyBuffer = 0.0;
        }
        d.speedBuffer = 0.0;
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onElytraBoost(PlayerElytraBoostEvent e) {
        this.store.get((Player)e.getPlayer()).lastElytraBoost = this.clock.getAsLong();
    }

    public static void registerImpulse(PlayerData d, double vy, long now) {
        double previous = d.impulseVy;
        boolean fresh = Double.isNaN(previous) || now - d.impulseAt > 1000L;
        d.impulseVy = fresh ? vy : Math.max(previous, vy);
        d.impulseAt = now;
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        PlayerData d = this.store.get(p);
        long now = this.clock.getAsLong();
        if (e.isCancelled() || !e.canBuild()) {
            d.lastGhostBlock = now;
            return;
        }
        if (this.exemptions.bypass(p) || this.bedrockExempt(p, d)) {
            return;
        }
        if (this.checks.get().place().onPlace(p, d, e.getBlockPlaced(), e.getBlockAgainst(), now)) {
            e.setCancelled(true);
            d.lastGhostBlock = now;
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent e) {
        if (e.isCancelled()) {
            this.store.get((Player)e.getPlayer()).lastGhostBlock = this.clock.getAsLong();
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Location to = e.getRespawnLocation();
        if (!validServerDestination(to)) {
            return;
        }
        PlayerData d = this.store.get(e.getPlayer());
        d.applyTeleport(to, this.clock.getAsLong(), false);
        d.resetBoatState(null);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Location to = e.getPlayer().getLocation();
        if (!validServerDestination(to)) {
            return;
        }
        PlayerData d = this.store.get(e.getPlayer());
        d.applyTeleport(to, this.clock.getAsLong(), false);
        d.resetBoatState(null);
    }

    private static boolean validServerDestination(Location to) {
        return to != null && to.getWorld() != null && !MovementChecks.invalidMovementPacket(to, to);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        long now;
        Player p = e.getPlayer();
        PlayerData d = this.store.get(p);
        d.lastSeen = now = this.clock.getAsLong();
        d.lastTeleport = now;
        d.lastSafe = p.getLocation().clone();
        d.lastSafeAt = now;
        d.fallPeakY = d.groundY = p.getLocation().getY();
        d.positionInitialized = true;
        d.bedrock = null;
        d.lastPositionChangeAt = now;
        d.resetPrediction(true);
        d.setSync(p.getLocation(), now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.store.remove(e.getPlayer().getUniqueId());
        this.debug.removeViewer(e.getPlayer().getUniqueId());
        this.debug.removeTarget(e.getPlayer().getUniqueId());
    }

    @FunctionalInterface
    public static interface ContextFactory {
        public MoveContext capture(Player var1, Location var2, Location var3, long var4, long var6, AttributeSet var8);
    }

    public record Checks(MovementChecks movement, BoatFlyCheck boat, VelocityCheck velocity, BlockPlaceChecks place, ElytraCheck elytra,
                         PredictionChecks prediction, HoverCheck hover) {
        public Checks(MovementChecks movement, BoatFlyCheck boat, VelocityCheck velocity, BlockPlaceChecks place, ElytraCheck elytra) {
            this(movement, boat, velocity, place, elytra, null, null);
        }
    }
}

