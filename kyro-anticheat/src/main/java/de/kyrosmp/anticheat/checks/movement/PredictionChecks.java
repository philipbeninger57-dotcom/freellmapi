package de.kyrosmp.anticheat.checks.movement;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.Collisions;
import de.kyrosmp.anticheat.core.MoveContext;
import de.kyrosmp.anticheat.core.Physics;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.SetbackService;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.Locale;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Tick-accurate movement prediction (3.7).
 *
 * <p>A vanilla client moves deterministically once per tick. While airborne its vertical velocity
 * follows {@code v' = (v - gravity) * 0.98}; horizontally the previous velocity is carried over with
 * the friction of the block it stood on and only a bounded input acceleration is added. Every
 * position event is compared with the largest motion vanilla physics allows from the previous
 * event, so flight, steps, speed and NoSlow are stopped on the first impossible tick instead of
 * after buffers fill up.
 *
 * <p>Paper only fires PlayerMoveEvent after 1/16 block of movement, so one event can contain
 * several ticks. The bounds below accept every merge that is physically possible and nothing more.
 * Server-sent motion (knockback, explosions, plugin velocity, mace smashes) is accepted through a
 * short impulse window. All judgements only ever reject the current move or restore a validated
 * position; nothing here kicks or bans.
 */
public final class PredictionChecks {
    static final double DRAG = (double) 0.98f;
    static final double AIR_FRICTION = (double) 0.91f;
    static final double GROUND_FRICTION = (double) (0.6f * 0.91f);
    /** Ice (0.98), packed ice and blue ice (0.989) all stay below air friction. */
    static final double SLIPPERY_FRICTION = AIR_FRICTION;
    static final double AIR_ACCEL = (double) 0.026f;
    static final double SPRINT_JUMP_BOOST = 0.2;
    static final double CLIMB_SPEED = 0.2;
    static final double MERGE_DISTANCE = 0.0625;
    /** Largest displacement a single event can have when it merges several slow ticks. */
    static final double MAX_MERGED_DISPLACEMENT = 0.25;
    /** A client standing in a chunk it has not received yet falls at a constant -0.1 * 0.98. */
    static final double UNLOADED_CHUNK_FALL = -0.1 * DRAG;
    static final long UNLOADED_CHUNK_WINDOW_MS = 10_000L;
    static final long GHOST_BLOCK_GRACE_MS = 1500L;
    static final long STRIKE_WINDOW_MS = 1000L;
    /** Horizontal moves up to this distance never matter for speed (and may be merged ticks). */
    static final double SLOW_MOVE = 0.2;
    static final double DEFAULT_STEP = 0.6;
    /** Below this no NoSlow verdict: two merged slowed ticks stay under it. */
    static final double NOSLOW_MIN = 0.13;
    private static final Set<String> IMPULSE_REASONS = Set.of("Velocity/knockback", "Recently damaged");
    private static final String CLIMB_REASON = "Liquid/climbable";

    private final FlagSink sink;
    private final SetbackService setback;
    private volatile CheckConfig cfg;
    private volatile StrictConfig strict;
    private volatile PreventionConfig prevention = PreventionConfig.defaults();
    /** Entity platforms (boats, shulkers, happy ghasts) are probed lazily; replaceable in tests. */
    volatile java.util.function.Predicate<Player> platformNearby = Collisions::platformEntityNearby;

    public PredictionChecks(FlagSink sink, SetbackService setback, CheckConfig cfg, StrictConfig strict) {
        this.sink = sink;
        this.setback = setback;
        this.cfg = cfg;
        this.strict = strict;
    }

    public PredictionChecks withPrevention(PreventionConfig policy) {
        this.prevention = policy;
        return this;
    }

    /**
     * Judges one position event and always updates the carried motion. Must run for every
     * processed move, exempt or not, so the prediction keeps following the real motion.
     *
     * @param prevGround ground state at the end of the previous event (start of this tick)
     * @param landed ground state at the end of this event
     * @param rejectedAlready another check already rejected this move
     * @return true when the move must be rejected
     */
    public boolean evaluate(Player p, PlayerData d, MoveContext m, boolean prevGround, boolean landed,
                            String exemptReason, boolean rejectedAlready) {
        StrictConfig s = this.strict;
        CheckConfig c = this.cfg;
        long now = m.now;
        double g = Physics.gravity(m.gravityAttr);
        boolean judged = !rejectedAlready && this.judgeable(d, m, exemptReason, now);
        boolean climbJudge = !rejectedAlready && !judged && CLIMB_REASON.equals(exemptReason)
                && this.judgeableBase(d, m, now) && m.geo.climbableNearby() && !m.geo.liquidNearby() && !m.geo.stuckNearby();
        double attr = this.speedAttribute(d, m, now);
        // Horizontal physics use exact ground contact (as vanilla does), not the lenient 0.12 block
        // check: friction comes from the tick before the previous one, acceleration from the last.
        double friction = d.predPrevGround && now - d.predImpulseAppliedAt > 200L
                ? (now - d.lastSlipperySurface < 1500L ? SLIPPERY_FRICTION : GROUND_FRICTION) : AIR_FRICTION;
        boolean startGround = d.predLastGroundStrict;
        boolean groundNow = landed && m.geo.groundStrict();

        boolean reject = false;
        String check = null;
        String details = null;
        double excess = 0.0;

        // ---------------- vertical: Fly B (gravity prediction, climbing) ----------------
        double carryVy;
        boolean takeoff = prevGround || Double.isNaN(d.predVy);
        double jump = Physics.jumpVelocity(m.jumpStrengthAttr, m.jumpAmplifier);
        double[] merged = takeoff ? null : merged(d.predVy, g, m.dy);
        if (merged != null && !Double.isNaN(d.predVyLow)) {
            // After an impulse the carried velocity is only known as a range: explain the event from
            // both ends and keep whatever is more lenient.
            double[] alt = merged(d.predVyLow, g, m.dy);
            for (int i = 0; i < merged.length; ++i) merged[i] = Math.max(merged[i], alt[i]);
        }
        double carryVyLow = Double.NaN;
        double tightVy = takeoff ? Math.max(jump, 0.0) : merged[0];
        boolean vyFromImpulse = false;
        boolean vyOverride = false;
        if (landed) {
            carryVy = Double.NaN;
        } else if (takeoff) {
            carryVy = takeoffCarry(m.dy, jump, g);
        } else {
            carryVy = merged[1];
        }
        double impulseTightVy = Double.NaN;
        boolean flyActive = s.flyPredictionEnabled() && c.flyEnabled() && g >= 0.03;
        if ((judged || climbJudge) && flyActive && !landed) {
            double tol = s.flyPredictionTolerance();
            double limit = tightVy + tol;
            String mode = takeoff ? "take-off" : "gravity";
            if (m.dy > limit && !takeoff && m.geo.fromGrounded()) {
                // A landing and the next jump merged into one event.
                takeoff = true;
                tightVy = Math.max(tightVy, jump);
                limit = tightVy + tol;
                mode = "take-off";
                vyOverride = true;
                carryVy = takeoffCarry(m.dy, jump, g);
            }
            if (m.dy > limit && climbJudge) {
                tightVy = Math.max(tightVy, CLIMB_SPEED);
                limit = tightVy + tol;
                mode = "climb";
                vyOverride = true;
            }
            if (m.dy > limit) {
                double impulse = openImpulseVy(d, m, now);
                if (!Double.isNaN(impulse)) {
                    double withImpulse = withImpulse(tightVy, impulse);
                    if (m.dy <= withImpulse + tol) {
                        vyFromImpulse = true;
                        impulseTightVy = tightVy;
                    }
                    limit = Math.max(limit, withImpulse + tol);
                }
            }
            if (m.dy > limit && now - d.lastTeleport < UNLOADED_CHUNK_WINDOW_MS
                    && Math.abs(m.dy - UNLOADED_CHUNK_FALL) < 0.004) {
                limit = m.dy;
                vyOverride = true;
            }
            if (m.dy > limit && !this.explainedByWorld(p, d, m, now)) {
                excess = m.dy - limit;
                check = "Fly B";
                details = String.format(Locale.US, "mode=%s; dy=%.4f; max=%.4f; excess=%.4f; previous_vy=%s; gravity=%.3f",
                        mode, m.dy, limit - tol, excess, Double.isNaN(d.predVy) ? "-" : String.format(Locale.US, "%.4f", d.predVy), g);
                reject = true;
            }
            if (vyFromImpulse || vyOverride && !takeoff) {
                carryVy = m.dy;
            } else if (vyOverride) {
                // take-off: carryVy already holds the take-off velocity
            } else if (!reject && !takeoff && merged != null) {
                // Never let an accepted upward deviation compound into the next prediction.
                carryVy = Math.min(carryVy, merged[2]);
                if (m.dy < merged[3] - tol && m.geo.ceiling()) {
                    // A head hit zeroes the velocity; slow ticks after it may merge into this event.
                    carryVy = Math.max(carryVy, restartCarry(m.dy, g));
                }
            }
        }

        // ---------------- Step A ----------------
        if (!reject && judged && s.stepEnabled() && prevGround && landed && m.dy > 0.0) {
            double step = Double.isFinite(m.stepHeightAttr) && m.stepHeightAttr >= 0.0 ? m.stepHeightAttr : DEFAULT_STEP;
            double allowance = Math.max(step, jump) + 0.02;
            if (m.dy > allowance) {
                double impulse = openImpulseVy(d, m, now);
                if (!Double.isNaN(impulse) && impulse > 0.0) allowance += impulse;
            }
            if (m.dy > allowance && now - d.lastBounceSurface > 1500L && !this.explainedByWorld(p, d, m, now)) {
                excess = m.dy - allowance;
                check = "Step A";
                details = String.format(Locale.US, "dy=%.4f; max_step=%.3f; step_height=%.2f; jump=%.3f", m.dy, allowance, step, jump);
                reject = true;
            }
        }

        // ---------------- horizontal: Speed B (momentum), NoSlow A ----------------
        double carryH = m.horizontal;
        boolean usedImpulseH = false;
        double limitHNoImpulse = Double.NaN;
        boolean speedActive = s.speedPredictionEnabled() && c.speedEnabled();
        if (!reject && judged && !Double.isNaN(d.predH)) {
            boolean jumped = startGround && m.dy > 0.0;
            double accel = startGround ? attr : AIR_ACCEL;
            double carried = d.predH * friction;
            // Paper merges ticks below 1/16 block into the next event. That is only possible while
            // the player moves slowly: the last observed event was slow, or the carried motion can
            // drop below 1/16 block within one tick.
            boolean mergeable = d.predHObserved <= SLOW_MOVE || carried - accel <= MERGE_DISTANCE;
            // Slow ticks before this one may have merged into the event in another direction, so the
            // last tick alone can be up to 1/16 block longer than the whole event.
            double mergeSlack = mergeable ? MERGE_DISTANCE : 0.0;
            double tightH = horizontalBound(carried, accel, attr, jumped, mergeable);
            double tolH = s.speedPredictionTolerance();
            boolean noSlow = s.noSlowEnabled() && m.usingItem && d.predUsingItem && m.itemUseTicks >= 3
                    && m.itemUseRemaining > 3 && m.itemUseSpeed < 0.85 && startGround && groundNow && !jumped;
            double tightSlow = noSlow ? horizontalBound(carried, attr * m.itemUseSpeed, attr * m.itemUseSpeed, false, mergeable) : Double.NaN;
            double limitH = tightH + tolH;
            if (m.horizontal > limitH && !jumped && m.dy > 0.0 && m.geo.fromGrounded()) {
                // A landing and the next (sprint) jump merged into one event. Checked before any
                // server impulse, so a pending knockback is not mistaken for it.
                jumped = true;
                tightH = Math.max(tightH, horizontalBound(carried, attr, attr, true, mergeable));
                limitH = tightH + tolH;
            }
            limitHNoImpulse = limitH;
            boolean hFromImpulse = false;
            if (m.horizontal > limitH || noSlow && m.horizontal > Math.max(tightSlow + tolH, NOSLOW_MIN)) {
                double impulse = openImpulseH(d, m, now);
                if (impulse > 0.0) {
                    limitH += impulse;
                    if (noSlow) tightSlow += impulse;
                    hFromImpulse = true;
                }
            }
            if (speedActive && m.horizontal > SLOW_MOVE && m.horizontal > limitH) {
                limitH += this.pushAllowance(p, m);
                if (m.horizontal > limitH && !this.explainedByWorld(p, d, m, now)) {
                    excess = m.horizontal - limitH;
                    check = "Speed B";
                    details = String.format(Locale.US, "horizontal=%.4f; max=%.4f; excess=%.4f; carried=%.4f; friction=%.3f; accel=%.4f; ground=%s",
                            m.horizontal, limitH - tolH, excess, d.predH, friction, accel, startGround);
                    reject = true;
                }
            }
            if (!reject && noSlow) {
                // Two slow ticks can merge into one event (Paper's 1/16 block filter).
                double limitSlow = Math.max(tightSlow + tolH, NOSLOW_MIN);
                if (m.horizontal > limitSlow) {
                    if (++d.noSlowMoves >= 2 && !this.explainedByWorld(p, d, m, now)) {
                        excess = m.horizontal - limitSlow;
                        check = "NoSlow A";
                        details = String.format(Locale.US, "horizontal=%.4f; max=%.4f; item_speed=%.2f; use_ticks=%d",
                                m.horizontal, limitSlow, m.itemUseSpeed, m.itemUseTicks);
                        reject = true;
                    }
                } else {
                    d.noSlowMoves = 0;
                }
            } else if (!noSlow) {
                d.noSlowMoves = 0;
            }
            if (hFromImpulse) {
                usedImpulseH = true;
            } else {
                // Never let an accepted deviation compound into the next prediction.
                carryH = Math.min(m.horizontal + mergeSlack, noSlow ? Math.max(tightSlow, 0.0) : tightH);
            }
        }

        // One server impulse explains both its vertical and horizontal part; the client applies it once.
        if (!reject && (vyFromImpulse || usedImpulseH)) {
            double[] bases = takeoff ? new double[]{-g * DRAG, jump} : new double[]{merged != null ? merged[3] : m.dy};
            double[] maxH = new double[1];
            double impulseCarry = this.consumeImpulse(d, m, now, vyFromImpulse ? impulseTightVy : Double.NaN, s.flyPredictionTolerance(),
                    usedImpulseH ? limitHNoImpulse : Double.NaN, bases, maxH);
            if (!Double.isNaN(impulseCarry)) {
                // The impulse may have landed in the last of several merged ticks: carry the largest
                // velocity it can have produced, and allow air friction on the next tick (an impulse
                // can lift the player off the ground for a tick).
                if (!landed) {
                    double observed = Double.isNaN(carryVy) ? m.dy : carryVy;
                    carryVy = Math.max(observed, impulseCarry);
                    if (carryVy > observed) carryVyLow = observed;
                }
                double hBase = Double.isNaN(limitHNoImpulse) ? m.horizontal : limitHNoImpulse;
                carryH = Math.max(carryH, hBase + maxH[0]);
                d.predImpulseAppliedAt = now;
            }
        }

        // ---------------- verdict ----------------
        if (reject) {
            reject = this.act(p, d, m, check, details, excess, now);
        } else if (judged) {
            d.flyPredBuffer = Math.max(0.0, d.flyPredBuffer - 0.02);
            d.speedPredBuffer = Math.max(0.0, d.speedPredBuffer - 0.05);
            d.stepBuffer = Math.max(0.0, d.stepBuffer - 0.02);
            d.noSlowBuffer = Math.max(0.0, d.noSlowBuffer - 0.05);
        }

        // ---------------- carry the motion into the next event ----------------
        if (reject || rejectedAlready) {
            // The server answers a rejected move with a position packet: zero velocity at 'from'.
            d.predVy = 0.0;
            d.predVyLow = Double.NaN;
            d.predH = 0.0;
            d.predHObserved = 0.0;
            d.predPrevGround = startGround;
            d.predUsingItem = m.usingItem;
            d.noSlowMoves = 0;
        } else {
            d.predVy = carryVy;
            d.predVyLow = carryVyLow;
            d.predH = carryH;
            d.predHObserved = m.horizontal;
            d.predPrevGround = startGround;
            d.predLastGroundStrict = groundNow;
            d.predUsingItem = m.usingItem;
        }
        d.predLastAt = now;
        return reject;
    }

    private boolean act(Player p, PlayerData d, MoveContext m, String check, String details, double excess, long now) {
        CheckConfig c = this.cfg;
        StrictConfig s = this.strict;
        double flagAt;
        boolean setbackAllowed;
        switch (check) {
            case "Fly B" -> {
                d.flyPredBuffer += 1.0 + Math.min(2.0, excess * 4.0);
                if (now - d.flyPredLastViolation > STRIKE_WINDOW_MS) d.flyPredStrikes = 0;
                ++d.flyPredStrikes;
                d.flyPredLastViolation = now;
                flagAt = s.flyPredictionFlagAt();
                setbackAllowed = c.flySetback();
            }
            case "Step A" -> {
                d.stepBuffer += 1.0;
                flagAt = s.stepFlagAt();
                setbackAllowed = c.flySetback();
            }
            case "NoSlow A" -> {
                d.noSlowBuffer += 1.0;
                flagAt = s.noSlowFlagAt();
                setbackAllowed = c.speedSetback();
            }
            default -> {
                d.speedPredBuffer += 1.0 + Math.min(1.0, excess * 2.0);
                if (now - d.speedPredLastViolation > STRIKE_WINDOW_MS) d.speedPredStrikes = 0;
                ++d.speedPredStrikes;
                d.speedPredLastViolation = now;
                flagAt = s.speedPredictionFlagAt();
                setbackAllowed = c.speedSetback();
            }
        }
        double buffer = switch (check) {
            case "Fly B" -> d.flyPredBuffer;
            case "Step A" -> d.stepBuffer;
            case "NoSlow A" -> d.noSlowBuffer;
            default -> d.speedPredBuffer;
        };
        boolean prevent = setbackAllowed && this.prevention.movement()
                && (this.prevention.instant() || buffer >= flagAt);
        // Fly: the first small deviation only rejects the tick (the client restarts at 'from' with
        // zero velocity). A repeat within one second or a large deviation returns the player to
        // validated ground, so hovering at 'from' is impossible.
        boolean toGround = prevent && "Fly B".equals(check) && (d.flyPredStrikes >= 2 || excess > 0.15);
        boolean grounded = false;
        if (toGround) {
            grounded = this.setback.setback(p, d, null);
        }
        if (buffer >= flagAt) {
            String id = this.sink.flag(p, d, check, reason(check),
                    details + String.format(Locale.US, "; buffer=%.1f; strikes=%d", buffer,
                            "Fly B".equals(check) ? d.flyPredStrikes : d.speedPredStrikes));
            if (id != null && prevent) {
                this.sink.action(id, grounded ? "Setback to validated ground" : "Move rejected");
            }
            switch (check) {
                case "Fly B" -> d.flyPredBuffer = 1.0;
                case "Step A" -> d.stepBuffer = 1.0;
                case "NoSlow A" -> d.noSlowBuffer = 1.0;
                default -> d.speedPredBuffer = 1.0;
            }
        }
        return prevent;
    }

    static String reason(String check) {
        return switch (check) {
            case "Fly B" -> "Vertical motion that gravity does not allow";
            case "Step A" -> "Stepped higher than the step height allows";
            case "NoSlow A" -> "Not slowed down while using an item";
            default -> "Faster than the carried momentum allows";
        };
    }

    /**
     * Largest horizontal displacement of one event: the carried velocity plus this tick's input
     * acceleration (and the sprint-jump boost). When the carried velocity is small enough for
     * Paper to merge slow ticks into one event, the merged ticks add at most 1/16 block and the
     * last tick starts from at most 1/16 * 0.91.
     */
    static double horizontalBound(double carried, double accel, double groundAccel, boolean jumped, boolean mergeable) {
        double jump = jumped ? SPRINT_JUMP_BOOST : 0.0;
        double single = carried + accel + jump;
        if (!mergeable) {
            return single;
        }
        // A landing can hide inside merged ticks: its last tick may use ground acceleration.
        return Math.max(single, MERGE_DISTANCE + MERGE_DISTANCE * AIR_FRICTION + Math.max(accel, groundAccel) + jump);
    }

    /** Situations in which no motion is judged at all (states are still tracked). */
    private boolean judgeable(PlayerData d, MoveContext m, String exemptReason, long now) {
        if (exemptReason != null && !IMPULSE_REASONS.contains(exemptReason)) return false;
        return this.judgeableBase(d, m, now);
    }

    private boolean judgeableBase(PlayerData d, MoveContext m, long now) {
        if (m.gameMode == GameMode.CREATIVE || m.gameMode == GameMode.SPECTATOR) return false;
        if (m.allowFlight || m.flying || m.gliding || m.insideVehicle || m.riptiding || m.swimming
                || m.levitation || m.slowFalling) return false;
        int lag = Math.min(1000, Math.max(0, m.ping));
        if (now - d.lastGhostBlock < GHOST_BLOCK_GRACE_MS + lag) return false;
        // Spears with Lunge propel the player when jabbing.
        return !(m.lungeWeapon && now - d.lastSwingAt < 800L + lag);
    }

    /** Pistons, shulker boxes, entity platforms and bounce blocks move players legitimately. */
    private boolean explainedByWorld(Player p, PlayerData d, MoveContext m, long now) {
        if (now - d.lastBounceSurface < 1500L) return true;
        if (m.geo.movingBlockNearby()) return true;
        try {
            return p != null && this.platformNearby.test(p);
        }
        catch (Throwable ex) {
            return true;
        }
    }

    /** Players and mobs push each other by up to 0.05 per axis and tick. */
    private double pushAllowance(Player p, MoveContext m) {
        if (p == null) return 0.0;
        try {
            int pushers = 0;
            for (org.bukkit.entity.Entity e : p.getNearbyEntities(0.8, 1.0, 0.8)) {
                if (e instanceof org.bukkit.entity.LivingEntity) ++pushers;
                if (pushers >= 4) break;
            }
            return 0.08 * pushers;
        }
        catch (Throwable ex) {
            return 0.3;
        }
    }

    /** Movement attribute including sprint, held at its recent peak to absorb removal latency. */
    double speedAttribute(PlayerData d, MoveContext m, long now) {
        double attr = m.movementSpeedAttr;
        if (!Double.isFinite(attr) || attr <= 0.0) {
            attr = 0.1 * Math.max(0.0, m.walkSpeed) / 0.2;
            if (m.speedAmplifier >= 0) attr *= 1.0 + 0.2 * (m.speedAmplifier + 1);
            if (m.sprinting) attr *= 1.3;
        }
        // Sprint can start client-side one tick before the server applies its modifier.
        if (!m.sprinting) attr *= 1.3;
        // A lower value is trusted only after it has been seen for ping + 600 ms: the client keeps
        // the old speed until the attribute/effect update reaches it.
        long hold = Math.min(3000L, 600L + Math.max(0, m.ping));
        if (Double.isNaN(d.predAttrPeak) || attr >= d.predAttrPeak) {
            d.predAttrPeak = attr;
            d.predAttrPeakAt = 0L;
        } else if (d.predAttrPeakAt == 0L) {
            d.predAttrPeakAt = now;
        } else if (now - d.predAttrPeakAt > hold) {
            d.predAttrPeak = attr;
            d.predAttrPeakAt = 0L;
        }
        return Math.max(attr, d.predAttrPeak);
    }

    static long impulseWindow(MoveContext m) {
        return Math.min(4000L, 1000L + 2L * Math.max(0, m.ping));
    }

    /** Largest vertical impulse the client may still apply, NaN when none is open. */
    static double openImpulseVy(PlayerData d, MoveContext m, long now) {
        long window = impulseWindow(m);
        double best = Double.NaN;
        for (int i = 0; i < PlayerData.IMPULSE_SLOTS; ++i) {
            if (d.impulseUsedRing[i] || now < d.impulseAtRing[i] || now - d.impulseAtRing[i] > window) continue;
            if (Double.isNaN(best) || d.impulseVyRing[i] > best) best = d.impulseVyRing[i];
        }
        return best;
    }

    /** Largest horizontal impulse the client may still apply, 0 when none is open. */
    static double openImpulseH(PlayerData d, MoveContext m, long now) {
        long window = impulseWindow(m);
        double best = 0.0;
        for (int i = 0; i < PlayerData.IMPULSE_SLOTS; ++i) {
            if (d.impulseUsedRing[i] || now < d.impulseAtRing[i] || now - d.impulseAtRing[i] > window) continue;
            best = Math.max(best, d.impulseHRing[i]);
        }
        return best;
    }

    /** Vertical bound when a server impulse either replaces or adds to the predicted velocity. */
    static double withImpulse(double predicted, double impulse) {
        return Math.max(predicted, impulse) + Math.max(0.0, Math.min(predicted, impulse));
    }

    /**
     * Velocity after a collision restarted the fall from rest within a merged event: the restart
     * sequence 0 -> -g*0.98 -> ... whose sum best explains the observed displacement.
     */
    static double restartCarry(double actual, double gravity) {
        double v = 0.0;
        double sum = 0.0;
        double best = Double.NEGATIVE_INFINITY;
        double bestErr = Double.POSITIVE_INFINITY;
        for (int k = 1; k <= 12; ++k) {
            v = (v - gravity) * DRAG;
            sum += v;
            double err = Math.abs(sum - actual);
            if (err < bestErr) {
                bestErr = err;
                best = v;
            }
            if (sum < actual - MERGE_DISTANCE) break;
        }
        return best;
    }

    /** Take-off velocity carried into the next tick: a jump, or the -g*0.98 start of a fall. */
    static double takeoffCarry(double dy, double jump, double gravity) {
        return dy > 0.0 ? Math.max(dy, jump) : Math.max(dy, -gravity * DRAG);
    }

    /**
     * The client applies each server impulse once and in the order the server sent them (one TCP
     * stream). The oldest open impulse that explains the observed motion is therefore the one that
     * was applied, and it is consumed. Impulses applied without being needed stay open until their
     * window ends - lenient, but only ever for motion the server itself sent.
     *
     * @param tightVy vertical bound without impulse, NaN if the vertical part needed none
     * @param limitH horizontal limit without impulse, NaN if the horizontal part needed none
     * @param bases velocities the last tick may have started from (prediction, jump, resting)
     * @return the largest vertical velocity any explaining impulse can have produced (NaN if none)
     */
    double consumeImpulse(PlayerData d, MoveContext m, long now, double tightVy, double tol, double limitH, double[] bases,
                          double[] maxH) {
        long window = impulseWindow(m);
        int oldest = -1;
        double carry = Double.NaN;
        maxH[0] = 0.0;
        for (int i = 0; i < PlayerData.IMPULSE_SLOTS; ++i) {
            if (d.impulseUsedRing[i] || now < d.impulseAtRing[i] || now - d.impulseAtRing[i] > window) continue;
            double vy = d.impulseVyRing[i];
            boolean explains = (Double.isNaN(tightVy) || m.dy <= withImpulse(tightVy, vy) + tol)
                    && (Double.isNaN(limitH) || m.horizontal <= limitH + d.impulseHRing[i]);
            if (!explains) continue;
            if (oldest < 0 || d.impulseAtRing[i] < d.impulseAtRing[oldest]) oldest = i;
            for (double b : bases) {
                if (Double.isFinite(b)) {
                    double v = withImpulse(b, vy);
                    if (Double.isNaN(carry) || v > carry) carry = v;
                }
            }
            maxH[0] = Math.max(maxH[0], d.impulseHRing[i]);
        }
        if (oldest >= 0) {
            d.impulseUsedRing[oldest] = true;
        }
        return carry;
    }

    /**
     * Largest vertical displacement one event may show after a previous airborne event whose last
     * tick moved {@code v0}, considering every tick merge Paper can produce.
     *
     * @return {highest reachable displacement, velocity of the last tick of the merge that best
     *         explains {@code actual} including the residual, the same velocity without residual}
     */
    static double[] merged(double v0, double gravity, double actual) {
        double v = v0;
        double sum = 0.0;
        double best = Double.NEGATIVE_INFINITY;
        double v1 = Double.NaN;
        double bestErr = Double.POSITIVE_INFINITY;
        double mergedCarry = Double.NEGATIVE_INFINITY;
        double mergedClean = Double.NEGATIVE_INFINITY;
        for (int k = 1; k <= 12; ++k) {
            v = (v - gravity) * DRAG;
            if (Math.abs(v) < 0.003) v = 0.0;
            sum += v;
            if (sum > best) best = sum;
            if (k == 1) {
                v1 = v;
            } else {
                double err = Math.abs(sum - actual);
                if (err < bestErr) {
                    bestErr = err;
                    mergedCarry = v + (actual - sum);
                    mergedClean = v;
                }
            }
            if (Math.abs(sum) > MERGE_DISTANCE) break;
        }
        // Single tick: a collision (ceiling) can only lower the motion, and vanilla then zeroes the
        // velocity, so the observed displacement is the safe carry. A merge is the other possible
        // explanation; the larger carry is kept so neither explanation is ever rejected later.
        double singleCarry = actual;
        double singleClean = Math.min(actual, v1);
        double carry = Math.max(singleCarry, mergedCarry);
        double clean = Math.max(singleClean, mergedClean);
        return new double[]{best, carry, clean, v1};
    }

    public void configure(CheckConfig cfg, StrictConfig strict) {
        this.cfg = cfg;
        this.strict = strict;
    }
}
