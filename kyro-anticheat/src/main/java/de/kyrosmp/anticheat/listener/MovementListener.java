package de.kyrosmp.anticheat.listener;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;

import de.kyrosmp.anticheat.checks.action.BlockPlaceChecks;
import de.kyrosmp.anticheat.checks.combat.VelocityCheck;
import de.kyrosmp.anticheat.checks.movement.ElytraCheck;
import de.kyrosmp.anticheat.checks.movement.HoverCheck;
import de.kyrosmp.anticheat.checks.movement.MovementChecks;
import de.kyrosmp.anticheat.checks.movement.PredictionChecks;
import de.kyrosmp.anticheat.checks.movement.SprintCheck;
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
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.core.TeleportSync;
import de.kyrosmp.anticheat.debug.DebugManager;
import de.kyrosmp.anticheat.flag.FlagSink;

import io.papermc.paper.event.player.PlayerFailMoveEvent;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class MovementListener implements Listener {
    private static final Set<String> AIR_GRACE = Set.of("Teleport", "Velocity/knockback");
    private static final Set<DamageCause> DISPLACING =
            Set.of(
                    DamageCause.ENTITY_ATTACK,
                    DamageCause.ENTITY_SWEEP_ATTACK,
                    DamageCause.PROJECTILE,
                    DamageCause.BLOCK_EXPLOSION,
                    DamageCause.ENTITY_EXPLOSION,
                    DamageCause.LIGHTNING,
                    DamageCause.FALLING_BLOCK,
                    DamageCause.FLY_INTO_WALL,
                    DamageCause.SONIC_BOOM,
                    DamageCause.CUSTOM);
    private final PlayerDataStore store;
    private final ExemptionManager exemptions;
    private final FlagSink sink;
    private final DebugManager debug;
    private final Supplier<MovementListener.Checks> checks;
    private final Supplier<CheckConfig> cfg;
    private final AttributeSet attributes;
    private final MovementListener.ContextFactory contexts;
    private final LongSupplier clock;
    private volatile Supplier<StrictConfig> strict = StrictConfig::defaults;

    public MovementListener withStrict(Supplier<StrictConfig> strict) {
        this.strict = strict;
        return this;
    }

    public MovementListener(
            PlayerDataStore store,
            ExemptionManager exemptions,
            FlagSink sink,
            DebugManager debug,
            Supplier<MovementListener.Checks> checks,
            Supplier<CheckConfig> cfg,
            AttributeSet attributes) {
        this(
                store,
                exemptions,
                sink,
                debug,
                checks,
                cfg,
                attributes,
                MoveContexts::capture,
                System::currentTimeMillis);
    }

    public MovementListener(
            PlayerDataStore store,
            ExemptionManager exemptions,
            FlagSink sink,
            DebugManager debug,
            Supplier<MovementListener.Checks> checks,
            Supplier<CheckConfig> cfg,
            AttributeSet attributes,
            MovementListener.ContextFactory contexts,
            LongSupplier clock) {
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMoveLowest(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to != null) {
            PlayerData d = this.store.getQuiet(e.getPlayer());
            d.lowestMoveEvent = e;
            d.lowestToX = to.getX();
            d.lowestToY = to.getY();
            d.lowestToZ = to.getZ();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        this.handleMove(e, this.clock.getAsLong(), System.nanoTime());
    }

    public void handleMove(PlayerMoveEvent e, long now, long nanoNow) {
        Location to = e.getTo();
        if (to != null) {
            Player p = e.getPlayer();
            PlayerData d = this.store.get(p);
            Location from = e.getFrom();
            d.preventMove = false;
            if (MovementChecks.invalidMovementPacket(from, to)) {
                d.lowestMoveEvent = null;
                if (this.cfg.get().badPacketsEnabled()
                        && !this.exemptions.bypass(p)
                        && !this.bedrockExempt(p, d)) {
                    this.sink.flag(
                            p,
                            d,
                            "BadPackets A",
                            "Invalid position or rotation values in movement packet",
                            "invalid movement values/pitch; pitch=" + to.getPitch());
                }

                e.setTo(from);
                e.setCancelled(true);
            } else {
                boolean redirected =
                        TeleportSync.redirectedByPlugin(d, e, to.getX(), to.getY(), to.getZ());
                d.lowestMoveEvent = null;
                this.syncFrom(p, d, from, now);
                if (redirected) {
                    this.handleRedirect(p, d, from, to, now);
                } else {
                    if (from.getWorld() != to.getWorld()
                            || TeleportSync.distSq(
                                            from.getX(),
                                            from.getY(),
                                            from.getZ(),
                                            to.getX(),
                                            to.getY(),
                                            to.getZ())
                                    > 1.0E-8) {
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
                    } finally {
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
            }
        }
    }

    private void syncFrom(Player p, PlayerData d, Location from, long now) {
        TeleportSync.Kind kind =
                TeleportSync.classifyFrom(
                        d, from.getWorld(), from.getX(), from.getY(), from.getZ(), now);
        switch (kind) {
            case CONTINUOUS:
            default:
                break;
            case CORRECTION:
                d.corrections++;
                d.setSync(from, now);
                d.resetPrediction(true);
                break;
            case OWN_SETBACK:
                d.applyTeleport(from, now, true);
                this.debug.event(
                        p.getUniqueId(),
                        () -> "§eOwn setback completed (detected from the move start)");
                break;
            case RELOCATION:
                d.relocations++;
                boolean worldChange = from.getWorld() != d.syncWorld;
                if (now - d.lastVehicle < 1500L) {
                    d.applyDismount(from, now);
                } else {
                    d.applyRelocation(from, now);
                }

                if (worldChange) {
                    d.lastBoatSafe = null;
                }

                long graceLeft =
                        Math.max(0L, this.cfg.get().graceTeleportMs() - (now - d.lastTeleport));
                this.debug.event(
                        p.getUniqueId(),
                        () ->
                                "§eServer relocation detected"
                                        + (worldChange ? " (world change)" : "")
                                        + " – teleport grace "
                                        + graceLeft
                                        + " ms");
        }
    }

    private void handleRedirect(Player p, PlayerData d, Location from, Location to, long now) {
        d.pluginRedirects++;
        double distSq =
                from.getWorld() == to.getWorld()
                        ? TeleportSync.distSq(
                                from.getX(),
                                from.getY(),
                                from.getZ(),
                                to.getX(),
                                to.getY(),
                                to.getZ())
                        : Double.POSITIVE_INFINITY;
        if (distSq <= 1.0) {
            d.setSync(to, now);
        } else {
            d.applyTeleport(to, now, false);
            this.debug.event(
                    p.getUniqueId(),
                    () -> "§eDestination changed by another plugin – treated as teleport");
        }
    }

    private boolean process(
            PlayerMoveEvent e,
            Player p,
            PlayerData d,
            Location from,
            Location to,
            long now,
            long nanoNow) {
        if (this.exemptions.bypass(p)) {
            d.resetPrediction(false);
            return false;
        } else {
            MovementChecks mc = this.checks.get().movement();
            if (this.bedrockExempt(p, d)) {
                d.coolMovementBuffers();
                d.resetPrediction(false);
                return false;
            } else if (mc.preventionEnabled()
                    && d.pendingSetback != null
                    && now >= d.pendingSetbackAt
                    && now - d.pendingSetbackAt < 1000L) {
                d.resetPrediction(true);
                return true;
            } else if (from.getWorld() != to.getWorld()) {
                return false;
            } else {
                MoveContext m = this.contexts.capture(p, from, to, now, nanoNow, this.attributes);
                if (m.total < 1.0E-4) {
                    return false;
                } else {
                    SprintCheck.noteFood(d, m.foodLevel, m.allowFlight, now);
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

                    MoveGeometry geo = m.geo;
                    boolean ground;
                    boolean touched =
                            !(ground = m.geo.ground())
                                    && m.dy <= 1.0E-9
                                    && geo.restingAtPreviousXZ();
                    boolean landed = ground || touched;
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
                    if (m.insideVehicle) {
                        // Vehicle movement is sent as vehicle packets, not player moves.
                        d.lastMoveNanos = 0L;
                    } else {
                        mc.checkTimer(p, d, m);
                    }

                    if (exempt) {
                        if (AIR_GRACE.contains(exemptReason)) {
                            d.speedBuffer = Math.max(0.0, d.speedBuffer - 0.8);
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

                    PredictionChecks prediction = this.checks.get().prediction();
                    if (prediction != null
                            && prediction.evaluate(
                                    p,
                                    d,
                                    m,
                                    prevGround,
                                    landed,
                                    exemptReason,
                                    reverted || d.preventMove)) {
                        d.preventMove = true;
                    }

                    d.lastMoveDy = m.dy;
                    reverted |= d.preventMove;
                    if (reverted) {
                        MovementChecks.rememberMove(d, from.getY(), d.lastGround, now);
                    }

                    if (!reverted && d.pendingSetback == null) {
                        mc.rememberSafe(d, m, ground, exempt);
                        d.history.addMove(
                                new PlayerHistory.MoveSample(
                                        now,
                                        m.toX,
                                        m.toY,
                                        m.toZ,
                                        m.yaw,
                                        m.pitch,
                                        m.dy,
                                        m.horizontal,
                                        ground,
                                        m.ping));
                    }

                    this.debug.move(
                            p.getUniqueId(),
                            now,
                            () ->
                                    String.format(
                                            Locale.US,
                                            "dy=%.3f h=%.3f ground=%s air=%d §8| §7buf speed=%.2f"
                                                + " fly=%.2f timer=%+.0fms spoof=%.1f jesus=%.1f"
                                                + " elytra=%.1f §8| §7%s",
                                            m.dy,
                                            m.horizontal,
                                            ground,
                                            d.airTicks,
                                            d.speedBuffer,
                                            d.flyBuffer,
                                            d.timerBalance,
                                            d.groundSpoofBuffer,
                                            d.jesusBuffer,
                                            d.elytraBuffer,
                                            exempt ? "§e" + exemptReason : "§aactive"));
                    return reverted;
                }
            }
        }
    }

    private void trackExemption(Player p, PlayerData d, String reason) {
        String before = d.lastExemptReason;
        d.lastExemptReason = reason;
        if (this.debug.isWatched(p.getUniqueId())) {
            if (!Objects.equals(before, reason)) {
                this.debug.event(
                        p.getUniqueId(),
                        () ->
                                reason == null
                                        ? "§aMovement checks active"
                                        : "§eExemption: " + reason);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMoveMonitor(PlayerMoveEvent e) {
        if (e.isCancelled()) {
            PlayerData d = this.store.peek(e.getPlayer().getUniqueId());
            if (d != null) {
                d.resetPrediction(true);
                d.lastPositionChangeAt = this.clock.getAsLong();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFailMove(PlayerFailMoveEvent e) {
        if (!e.isAllowed()) {
            PlayerData d = this.store.peek(e.getPlayer().getUniqueId());
            if (d != null) {
                d.resetPrediction(false);
                d.lastPositionChangeAt = this.clock.getAsLong();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void protectSetback(PlayerTeleportEvent e) {
        if (e.isCancelled()
                && e.getCause() == TeleportCause.PLUGIN
                && this.strict.get().protectSetbacks()) {
            PlayerData d = this.store.peek(e.getPlayer().getUniqueId());
            Location to = e.getTo();
            Location pending = d == null ? null : d.pendingSetback;
            if (pending != null
                    && to != null
                    && to.getWorld() == pending.getWorld()
                    && d.hasFreshPendingSetback(this.clock.getAsLong())) {
                if (TeleportSync.distSq(
                                to.getX(),
                                to.getY(),
                                to.getZ(),
                                pending.getX(),
                                pending.getY(),
                                pending.getZ())
                        <= 1.0E-6) {
                    e.setCancelled(false);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (e.getVehicle() instanceof Boat boat) {
            Player driver = null;

            for (Entity passenger : boat.getPassengers()) {
                if (passenger instanceof Player) {
                    Player pl;
                    driver = pl = (Player) passenger;
                    break;
                }
            }

            if (driver != null && !this.exemptions.bypass(driver)) {
                PlayerData d = this.store.get(driver);
                if (!this.bedrockExempt(driver, d)) {
                    Location from = e.getFrom();
                    Location to = e.getTo();
                    if (from != null && to != null && from.getWorld() == to.getWorld()) {
                        this.checks
                                .get()
                                .boat()
                                .check(driver, d, boat, from, to, this.clock.getAsLong());
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p) {
            PlayerData d = this.store.get(p);
            d.lastVehicle = this.clock.getAsLong();
            d.resetBoatState(e.getVehicle().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) {
            PlayerData d = this.store.get(p);
            d.lastVehicle = this.clock.getAsLong();
            d.fallPeakY = d.groundY = p.getLocation().getY();
            d.resetBoatState(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFallDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            PlayerData d = this.store.get(p);
            long now = this.clock.getAsLong();
            if (e.getCause() == DamageCause.FALL) {
                d.lastFallDamage = now;
            } else {
                if (!e.isCancelled() && displacing(e.getCause())) {
                    d.lastDamage = now;
                }
            }
        }
    }

    public static boolean displacing(DamageCause cause) {
        return DISPLACING.contains(cause);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location to = e.getTo();
        if (!validServerDestination(to)) {
            e.setCancelled(true);
        } else {
            PlayerData d = this.store.get(e.getPlayer());
            long now = this.clock.getAsLong();
            TeleportSync.Kind kind = TeleportSync.classifyTeleport(d, to, now);
            switch (kind) {
                case CORRECTION:
                    d.corrections++;
                    d.setSync(to, now);
                    d.resetPrediction(true);
                    return;
                case OWN_SETBACK:
                    d.applyTeleport(to, now, true);
                    break;
                default:
                    if (e.getCause() == TeleportCause.DISMOUNT) {
                        d.applyDismount(to, now);
                    } else {
                        d.applyTeleport(to, now, false);
                    }
            }

            this.checks.get().velocity().cancelPending(d);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVelocityLowest(PlayerVelocityEvent e) {
        PlayerData d = this.store.getQuiet(e.getPlayer());
        d.lowestVelocityEvent = e;

        try {
            d.lowestVelocity = e.getVelocity().clone();
        } catch (Throwable ex) {
            d.lowestVelocity = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        PlayerData d = this.store.get(e.getPlayer());
        Vector sent = null;

        try {
            sent = e.getVelocity();
        } catch (Throwable ignored) {
        }

        Vector original = d.lowestVelocityEvent == e ? d.lowestVelocity : null;
        d.lowestVelocityEvent = null;
        boolean modified =
                original != null && sent != null && original.distanceSquared(sent) > 1.0E-8;
        this.checks
                .get()
                .velocity()
                .onVelocityEvent(d, sent, e.isCancelled(), modified, this.clock.getAsLong());
        if (!e.isCancelled()) {
            double strength = 0.0;
            double vy = 0.0;

            try {
                Vector v = e.getVelocity();
                strength = v.length();
                vy = v.getY();
            } catch (Throwable ignored) {
            }

            long now = this.clock.getAsLong();
            applyVelocityGrace(d, strength, now, this.cfg.get());
            registerImpulse(d, vy, now);
            if (sent != null) {
                d.registerPredictionImpulse(sent.getX(), sent.getY(), sent.getZ(), now);
            }
        }
    }

    public static void applyVelocityGrace(PlayerData d, double strength, long now, CheckConfig c) {
        d.lastVelocity = now;
        d.velocityGraceUntil =
                Math.max(
                        d.velocityGraceUntil,
                        now + c.graceVelocityMs() + (long) Math.min(4500.0, strength * 2500.0));
        if (!d.graceFlyEvidence) {
            d.flyBuffer = 0.0;
        }

        d.speedBuffer = 0.0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent e) {
        this.store.get(e.getPlayer()).lastElytraBoost = this.clock.getAsLong();
    }

    public static void registerImpulse(PlayerData d, double vy, long now) {
        double previous = d.impulseVy;
        boolean fresh = Double.isNaN(previous) || now - d.impulseAt > 1000L;
        d.impulseVy = fresh ? vy : Math.max(previous, vy);
        d.impulseAt = now;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        PlayerData d = this.store.get(p);
        long now = this.clock.getAsLong();
        if (e.isCancelled() || !e.canBuild()) {
            d.lastGhostBlock = now;
        } else if (!this.exemptions.bypass(p) && !this.bedrockExempt(p, d)) {
            if (this.checks
                    .get()
                    .place()
                    .onPlace(p, d, e.getBlockPlaced(), e.getBlockAgainst(), now)) {
                e.setCancelled(true);
                d.lastGhostBlock = now;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent e) {
        if (e.isCancelled()) {
            this.store.get(e.getPlayer()).lastGhostBlock = this.clock.getAsLong();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Location to = e.getRespawnLocation();
        if (validServerDestination(to)) {
            PlayerData d = this.store.get(e.getPlayer());
            d.applyTeleport(to, this.clock.getAsLong(), false);
            d.resetBoatState(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Location to = e.getPlayer().getLocation();
        if (validServerDestination(to)) {
            PlayerData d = this.store.get(e.getPlayer());
            d.applyTeleport(to, this.clock.getAsLong(), false);
            d.resetBoatState(null);
        }
    }

    private static boolean validServerDestination(Location to) {
        return to != null && to.getWorld() != null && !MovementChecks.invalidMovementPacket(to, to);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleSprint(PlayerToggleSprintEvent e) {
        if (!e.isSprinting()) {
            return;
        }
        Player p = e.getPlayer();
        PlayerData d = this.store.get(p);
        Checks c = this.checks.get();
        GameMode gm = p.getGameMode();
        if (c.sprint() == null
                || this.exemptions.bypass(p)
                || this.bedrockExempt(p, d)
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || p.isInsideVehicle()) {
            return;
        }
        long now = this.clock.getAsLong();
        if (c.sprint()
                .onSprintStart(
                        p, d, p.getFoodLevel(), p.getAllowFlight(), Punisher.safePing(p), now)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) {
            PlayerData d = this.store.peek(p.getUniqueId());
            if (d != null) {
                // The client still saw the old value until now.
                SprintCheck.noteFood(
                        d, p.getFoodLevel(), p.getAllowFlight(), this.clock.getAsLong());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerData d = this.store.get(p);
        long now;
        d.lastSeen = now = this.clock.getAsLong();
        d.lastFoodAbove6At = now;
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

    public static record Checks(
            MovementChecks movement,
            BoatFlyCheck boat,
            VelocityCheck velocity,
            BlockPlaceChecks place,
            ElytraCheck elytra,
            PredictionChecks prediction,
            HoverCheck hover,
            SprintCheck sprint) {
        public Checks(
                MovementChecks movement,
                BoatFlyCheck boat,
                VelocityCheck velocity,
                BlockPlaceChecks place,
                ElytraCheck elytra,
                PredictionChecks prediction,
                HoverCheck hover) {
            this(movement, boat, velocity, place, elytra, prediction, hover, null);
        }

        public Checks(
                MovementChecks movement,
                BoatFlyCheck boat,
                VelocityCheck velocity,
                BlockPlaceChecks place,
                ElytraCheck elytra) {
            this(movement, boat, velocity, place, elytra, null, null, null);
        }
    }

    @FunctionalInterface
    public interface ContextFactory {
        MoveContext capture(
                Player player,
                Location from,
                Location to,
                long now,
                long nanoNow,
                AttributeSet attributes);
    }
}
