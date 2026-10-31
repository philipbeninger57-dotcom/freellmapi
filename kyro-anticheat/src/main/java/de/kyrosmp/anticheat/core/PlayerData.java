/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Location
 *  org.bukkit.World
 *  org.bukkit.util.Vector
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.PlayerHistory;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

public final class PlayerData {
    public static final long SETBACK_GRACE_OFFSET_MS = 2400L;
    public static final long SETBACK_STALE_MS = 5000L;
    public final UUID uuid;
    public volatile String name;
    public volatile String world = "?";
    public volatile int ping;
    public volatile Boolean bedrock;
    public final ConcurrentLinkedDeque<Long> flyFlags = new ConcurrentLinkedDeque();
    public final ConcurrentLinkedDeque<Long> boatFlyFlags = new ConcurrentLinkedDeque();
    public volatile long lastTeleport;
    public volatile long lastVelocity;
    public volatile long velocityGraceUntil;
    public volatile long lastGlide;
    public volatile long lastRiptide;
    public volatile long lastSwimming;
    public volatile long lastVehicle;
    public volatile long lastLiquid;
    public volatile long lastBounceSurface;
    public volatile long lastSlipperySurface;
    public volatile long lastGhostBlock;
    public volatile long lastDamage;
    public volatile long lastFallDamage;
    public volatile long lastMove;
    public volatile long lastSeen;
    public volatile long lastAttack;
    public volatile long lastMaceSmashAt;
    public volatile long lastMoveNanos;
    public volatile long timerGraceUntil;
    public volatile long landedAt;
    public volatile long lastSafeAt;
    public volatile long switchWindowStart;
    public volatile long lastBoatSafeAt;
    public volatile long lastBoatSupported;
    public volatile int airTicks;
    public long airSince;
    public volatile int hoverTicks;
    public volatile long unsupportedHoverSince;
    public volatile long unsupportedHoverLastAt;
    public volatile double unsupportedHoverY;
    public volatile int fastTargetSwitches;
    public volatile UUID lastTarget;
    public volatile double lastY;
    public volatile double groundY;
    public volatile double fallPeakY;
    public volatile double speedBuffer;
    public volatile double flyBuffer;
    public volatile double timerBalance;
    public volatile double reachBuffer;
    public volatile double angleBuffer;
    public volatile double boatFlyBuffer;
    public volatile double groundSpoofBuffer;
    public volatile double landedFallDistance;
    public Location fallSafe;
    public volatile boolean lastGround;
    public volatile boolean positionInitialized;
    public volatile Location lastSafe;
    public volatile Location lastBoatSafe;
    public volatile Location pendingSetback;
    public boolean preventMove;
    public final ArrayDeque<Location> safePositions = new ArrayDeque<>();
    public volatile long boatSetbackAt;
    public volatile int jesusTicks;
    public long jesusSince;
    public long jesusLastAt;
    public volatile double jesusBuffer;
    public volatile double lastMoveHorizontal;
    public volatile double prevMoveHorizontal;
    public volatile double inventoryMoveBuffer;
    public volatile boolean inventorySessionActive;
    public volatile long inventoryOpenedAt;
    public volatile long inventoryEvidenceMoveAt;
    public volatile double scaffoldBuffer;
    public volatile double fastPlaceBuffer;
    public final ArrayDeque<Long> placeTimes = new ArrayDeque();
    public volatile long swingSecondStart;
    public volatile int swingCount;
    public volatile int highCpsSeconds;
    public volatile double velocityBuffer;
    public volatile long kbTime;
    public volatile double kbDirX;
    public volatile double kbDirZ;
    public volatile double kbStrength;
    public volatile double kbMaxProjection;
    public volatile int kbMoves;
    public volatile boolean groundSinceFlyIncident = true;
    public volatile boolean syncValid;
    public volatile World syncWorld;
    public volatile double syncX;
    public volatile double syncY;
    public volatile double syncZ;
    public volatile long syncAt;
    public volatile long teleportsApplied;
    public volatile Object lowestMoveEvent;
    public volatile double lowestToX;
    public volatile double lowestToY;
    public volatile double lowestToZ;
    public volatile long relocations;
    public volatile long corrections;
    public volatile long pluginRedirects;
    public volatile long pendingSetbackAt;
    public volatile long setbackSeq;
    public volatile long setbacksRequested;
    public volatile long setbacksCompleted;
    public volatile long setbacksFailed;
    public volatile long setbacksSkippedInFlight;
    public volatile double impulseY = Double.NaN;
    public volatile double impulseVy = Double.NaN;
    public volatile long impulseAt;
    public volatile boolean graceFlyEvidence;
    public volatile long lastElytraBoost;
    public volatile double elytraCeilingMin = Double.NaN;
    public volatile int elytraHoverTicks;
    public long elytraHoverSince;
    public volatile int elytraFastTicks;
    public volatile double elytraBuffer;
    public volatile World elytraSafeWorld;
    public volatile double elytraSafeX;
    public volatile double elytraSafeY;
    public volatile double elytraSafeZ;
    public volatile long elytraSafeAt;
    public volatile World elytraCandWorld;
    public volatile double elytraCandX;
    public volatile double elytraCandY;
    public volatile double elytraCandZ;
    public volatile long elytraCandAt;
    public volatile String lastElytraFlagId;
    public final ArrayDeque<Long> elytraIncidents = new ArrayDeque();
    public volatile int dryOverLiquidTicks;
    public volatile double wallHitBuffer;
    public volatile Object lowestVelocityEvent;
    public volatile Vector lowestVelocity;
    public volatile double criticalsBuffer;
    public final long[] xrayMinedRing = PlayerData.filled(128);
    public final long[] xrayOreRing = PlayerData.filled(32);
    public int xrayMinedHead;
    public int xrayOreHead;
    public volatile World xrayWorld;
    public volatile UUID boatId;
    public volatile double lastBoatDy = Double.NaN;
    public volatile long boatHoverSince;
    public long boatAirSince;
    public volatile long boatHoverLastAt;
    public volatile double lastAttackDirX;
    public volatile double lastAttackDirY;
    public volatile double lastAttackDirZ;
    public volatile long lastDrop;
    public long lastDropSwingExemptAt;
    public final PlayerHistory history = new PlayerHistory(60, 20);
    public volatile String lastExemptReason;
    // ---- 3.7 prediction state (owned by the player's region thread) ----
    /** Vertical velocity carried into the next tick; NaN = unknown (use take-off rules). */
    public double predVy = Double.NaN;
    /** Lower end of the carried vertical velocity when it is only known as a range (after impulses). */
    public double predVyLow = Double.NaN;
    /** Horizontal velocity carried into the next tick; NaN = unknown. */
    public double predH = Double.NaN;
    /** Horizontal displacement actually observed in the previous event. */
    public double predHObserved;
    /** Ground state at the end of the event before the previous one (friction of the carried velocity). */
    public boolean predPrevGround;
    /** Exact ground contact (as vanilla's onGround) at the end of the previous event. */
    public boolean predLastGroundStrict = true;
    public long predLastAt;
    public double predAttrPeak = Double.NaN;
    public long predAttrPeakAt;
    /** Small ring of server-sent impulses (velocity, knockback, explosions) the client may still apply. */
    public static final int IMPULSE_SLOTS = 4;
    public final double[] impulseVyRing = new double[IMPULSE_SLOTS];
    public final double[] impulseHRing = new double[IMPULSE_SLOTS];
    public final long[] impulseAtRing = new long[IMPULSE_SLOTS];
    public final boolean[] impulseUsedRing = {true, true, true, true};
    public int impulseNext;
    public long predImpulseAppliedAt;
    public double flyPredBuffer;
    public double speedPredBuffer;
    public double stepBuffer;
    public double noSlowBuffer;
    public int flyPredStrikes;
    public long flyPredLastViolation;
    public int speedPredStrikes;
    public long speedPredLastViolation;
    public int noSlowMoves;
    public boolean predUsingItem;
    public volatile long lastPositionChangeAt;
    public volatile long lastSwingAt;
    public int hoverStillChecks;
    public double hoverBuffer;
    public final ArrayDeque<Long> swingTimes = new ArrayDeque<>();
    public final ArrayDeque<Long> digTimes = new ArrayDeque<>();
    public volatile World digWorld;
    public volatile int digX;
    public volatile int digY;
    public volatile int digZ;
    public volatile long digAt;
    public double nukerBuffer;
    public double ghostHandBuffer;
    public double scaffoldFaceBuffer;
    public volatile double lastMoveDy;

    /** Records a motion change the server sent to the client; it may apply it within ping time. */
    public void registerPredictionImpulse(double vx, double vy, double vz, long now) {
        if (!Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)) return;
        int slot = this.impulseNext;
        this.impulseNext = (slot + 1) % IMPULSE_SLOTS;
        this.impulseVyRing[slot] = vy;
        this.impulseHRing[slot] = Math.sqrt(vx * vx + vz * vz);
        this.impulseAtRing[slot] = now;
        this.impulseUsedRing[slot] = false;
    }

    /** The client always restarts with zero velocity after a server position packet. */
    public void resetPrediction(boolean velocityKnownZero) {
        this.predVy = velocityKnownZero ? 0.0 : Double.NaN;
        this.predVyLow = Double.NaN;
        this.predH = velocityKnownZero ? 0.0 : Double.NaN;
        this.predHObserved = 0.0;
        // Unknown support: assume ground (the larger acceleration) for the first tick.
        this.predLastGroundStrict = true;
        this.predPrevGround = true;
        java.util.Arrays.fill(this.impulseUsedRing, true);
        // Strikes are kept on purpose: a rejected move is followed by exactly this reset, and the
        // repeat within one second must still escalate to a ground setback. They expire by time.
        this.noSlowMoves = 0;
        this.hoverStillChecks = 0;
    }

    private static long[] filled(int n) {
        long[] a = new long[n];
        Arrays.fill(a, Long.MIN_VALUE);
        return a;
    }

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public void coolMovementBuffers() {
        this.speedBuffer = Math.max(0.0, this.speedBuffer - 0.8);
        this.flyBuffer = Math.max(0.0, this.flyBuffer - 0.8);
        this.timerBalance = Math.min(this.timerBalance, 0.0);
        this.airTicks = 0;
        this.airSince = 0;
        this.hoverTicks = 0;
        this.unsupportedHoverSince = 0L;
        this.unsupportedHoverLastAt = 0L;
        this.unsupportedHoverY = Double.NaN;
    }

    public void setSync(Location at, long now) {
        if (at == null || at.getWorld() == null || !Double.isFinite(at.getX())
                || !Double.isFinite(at.getY()) || !Double.isFinite(at.getZ())) {
            this.syncValid = false;
            return;
        }
        this.syncAt = now;
        this.syncWorld = at.getWorld();
        this.syncX = at.getX();
        this.syncY = at.getY();
        this.syncZ = at.getZ();
        this.syncValid = true;
    }

    public boolean hasFreshPendingSetback(long now) {
        long age = now - this.pendingSetbackAt;
        return this.pendingSetback != null && age >= 0L && age <= SETBACK_STALE_MS;
    }

    public void applyTeleport(Location to, long now, boolean ownSetback) {
        if (!validPosition(to)) return;
        this.safePositions.clear();
        this.fallSafe = null;
        this.lastBoatSafe = null;
        this.lastBoatSafeAt = 0;
        ++this.teleportsApplied;
        if (ownSetback) {
            // An anti-cheat correction is not a fresh server-teleport exemption.
            // Otherwise every correction buys another unchecked speed burst.
            this.lastTeleport = now - 30001L;
            this.pendingSetback = null;
            this.groundSinceFlyIncident = true;
        } else {
            this.lastTeleport = now;
            if (this.pendingSetback != null) {
                this.pendingSetback = null;
                ++this.setbackSeq;
            }
        }
        // A server teleport does not prove that its destination has support.
        // Own validated setbacks retain an anchor; other teleports reacquire one
        // from the first checked ground movement after the grace period.
        this.lastSafe = ownSetback ? to.clone() : null;
        this.elytraSafeAt = 0L;
        this.elytraCandAt = 0L;
        this.history.clearMoves();
        this.lastSafeAt = now;
        if (to != null) {
            this.groundY = to.getY();
            this.fallPeakY = to.getY();
            this.lastY = to.getY();
        }
        this.landedAt = 0L;
        this.impulseY = Double.NaN;
        this.impulseVy = Double.NaN;
        this.graceFlyEvidence = false;
        this.elytraCeilingMin = Double.NaN;
        double timerDebt = this.timerBalance;
        this.coolMovementBuffers();
        if (ownSetback && timerDebt > 0) this.timerBalance = timerDebt;
        this.kbTime = 0L;
        // Absolute server teleports restart the client with zero velocity.
        this.resetPrediction(true);
        this.lastPositionChangeAt = now;
        this.setSync(to, now);
    }

    public void applyRelocation(Location to, long now) {
        if (!validPosition(to)) return;
        long since = this.syncAt;
        this.applyTeleport(to, now, false);
        this.lastTeleport = Math.max(Math.min(since, now), now - 2400L);
    }

    public void applyDismount(Location to, long now) {
        if (!validPosition(to)) return;
        if (to != null) {
            this.groundY = to.getY();
            this.fallPeakY = to.getY();
            this.lastY = to.getY();
        }
        this.landedAt = 0L;
        this.impulseY = Double.NaN;
        this.impulseVy = Double.NaN;
        this.coolMovementBuffers();
        this.kbTime = 0L;
        ++this.teleportsApplied;
        // Leaving a vehicle keeps an unknown amount of its motion.
        this.resetPrediction(false);
        this.lastPositionChangeAt = now;
        this.setSync(to, now);
    }

    private static boolean validPosition(Location at) {
        return at != null && at.getWorld() != null && Double.isFinite(at.getX())
                && Double.isFinite(at.getY()) && Double.isFinite(at.getZ())
                && Float.isFinite(at.getYaw()) && Float.isFinite(at.getPitch());
    }

    public void resetBoatState(UUID newBoat) {
        this.boatAirSince = 0;
        this.boatSetbackAt = 0L;
        this.boatId = newBoat;
        this.lastBoatSafe = null;
        this.lastBoatSafeAt = 0L;
        this.lastBoatSupported = 0L;
        this.boatFlyBuffer = 0.0;
        this.lastBoatDy = Double.NaN;
        this.boatHoverSince = 0L;
        this.boatHoverLastAt = 0L;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void resetBuffers() {
        this.coolMovementBuffers();
        this.groundSpoofBuffer = 0.0;
        this.boatFlyBuffer = 0.0;
        this.angleBuffer = 0.0;
        this.reachBuffer = 0.0;
        this.flyBuffer = 0.0;
        this.speedBuffer = 0.0;
        this.elytraBuffer = 0.0;
        this.velocityBuffer = 0.0;
        this.fastPlaceBuffer = 0.0;
        this.scaffoldBuffer = 0.0;
        this.inventoryMoveBuffer = 0.0;
        this.inventorySessionActive = false;
        this.inventoryOpenedAt = 0L;
        this.inventoryEvidenceMoveAt = 0L;
        this.jesusBuffer = 0.0;
        this.dryOverLiquidTicks = 0;
        this.elytraFastTicks = 0;
        this.elytraHoverTicks = 0;
        this.highCpsSeconds = 0;
        this.fastTargetSwitches = 0;
        this.jesusTicks = 0;
        this.wallHitBuffer = 0.0;
        this.criticalsBuffer = 0.0;
        this.flyPredBuffer = 0.0;
        this.speedPredBuffer = 0.0;
        this.stepBuffer = 0.0;
        this.noSlowBuffer = 0.0;
        this.hoverBuffer = 0.0;
        this.nukerBuffer = 0.0;
        this.ghostHandBuffer = 0.0;
        this.scaffoldFaceBuffer = 0.0;
        ArrayDeque<Long> arrayDeque = this.elytraIncidents;
        synchronized (arrayDeque) {
            this.elytraIncidents.clear();
        }
        this.flyFlags.clear();
        this.boatFlyFlags.clear();
    }
}

