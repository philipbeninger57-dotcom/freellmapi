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

import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class PredictionChecks {
    static final double DRAG = 0.98F;
    static final double AIR_FRICTION = 0.91F;
    static final double GROUND_FRICTION = 0.54600006F;
    static final double SLIPPERY_FRICTION = 0.91F;
    static final double AIR_ACCEL = 0.026F;
    static final double SPRINT_JUMP_BOOST = 0.2;
    static final double CLIMB_SPEED = 0.2;
    static final double MERGE_DISTANCE = 0.0625;
    static final double MAX_MERGED_DISPLACEMENT = 0.25;
    static final double UNLOADED_CHUNK_FALL = -0.09800000190734864;
    static final long UNLOADED_CHUNK_WINDOW_MS = 10000L;
    static final long GHOST_BLOCK_GRACE_MS = 1500L;
    static final long STRIKE_WINDOW_MS = 1000L;
    static final double SLOW_MOVE = 0.2;
    static final double DEFAULT_STEP = 0.6;
    static final double NOSLOW_MIN = 0.13;
    private static final Set<String> IMPULSE_REASONS =
            Set.of("Velocity/knockback", "Recently damaged");
    private static final String CLIMB_REASON = "Liquid/climbable";
    private final FlagSink sink;
    private final SetbackService setback;
    private volatile CheckConfig cfg;
    private volatile StrictConfig strict;
    private volatile PreventionConfig prevention = PreventionConfig.defaults();
    volatile Predicate<Player> platformNearby = Collisions::platformEntityNearby;

    public PredictionChecks(
            FlagSink sink, SetbackService setback, CheckConfig cfg, StrictConfig strict) {
        this.sink = sink;
        this.setback = setback;
        this.cfg = cfg;
        this.strict = strict;
    }

    public PredictionChecks withPrevention(PreventionConfig policy) {
        this.prevention = policy;
        return this;
    }

    public boolean evaluate(
            Player p,
            PlayerData d,
            MoveContext m,
            boolean prevGround,
            boolean landed,
            String exemptReason,
            boolean rejectedAlready) {
        StrictConfig s = this.strict;
        CheckConfig c = this.cfg;
        long now = m.now;
        double g = Physics.gravity(m.gravityAttr);
        boolean judged = !rejectedAlready && this.judgeable(d, m, exemptReason, now);
        boolean climbJudge =
                !rejectedAlready
                        && !judged
                        && "Liquid/climbable".equals(exemptReason)
                        && this.judgeableBase(d, m, now)
                        && m.geo.climbableNearby()
                        && !m.geo.liquidNearby()
                        && !m.geo.stuckNearby();
        boolean sprintPossible = sprintPossible(d, m, now);
        double attr = this.speedAttribute(d, m, now, sprintPossible);
        double friction =
                d.predPrevGround && now - d.predImpulseAppliedAt > 200L
                        ? (now - d.lastSlipperySurface < 1500L ? 0.91F : 0.54600006F)
                        : 0.91F;
        boolean startGround = d.predLastGroundStrict;
        boolean groundNow = landed && m.geo.groundStrict();
        boolean reject = false;
        String check = null;
        String details = null;
        double excess = 0.0;
        boolean takeoff = prevGround || Double.isNaN(d.predVy);
        double jump = Physics.jumpVelocity(m.jumpStrengthAttr, m.jumpAmplifier);
        double[] merged = takeoff ? null : merged(d.predVy, g, m.dy);
        if (merged != null && !Double.isNaN(d.predVyLow)) {
            double[] alt = merged(d.predVyLow, g, m.dy);

            for (int i = 0; i < merged.length; i++) {
                merged[i] = Math.max(merged[i], alt[i]);
            }
        }

        double carryVyLow = Double.NaN;
        double tightVy = takeoff ? Math.max(jump, 0.0) : merged[0];
        boolean vyFromImpulse = false;
        boolean vyOverride = false;
        double carryVy;
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
                takeoff = true;
                tightVy = Math.max(tightVy, jump);
                limit = tightVy + tol;
                mode = "take-off";
                vyOverride = true;
                carryVy = takeoffCarry(m.dy, jump, g);
            }

            if (m.dy > limit && climbJudge) {
                tightVy = Math.max(tightVy, 0.2);
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

            if (m.dy > limit
                    && now - d.lastTeleport < 10000L
                    && Math.abs(m.dy - -0.09800000190734864) < 0.004) {
                limit = m.dy;
                vyOverride = true;
            }

            if (m.dy > limit && !this.explainedByWorld(p, d, m, now)) {
                excess = m.dy - limit;
                check = "Fly B";
                details =
                        String.format(
                                Locale.US,
                                "mode=%s; dy=%.4f; max=%.4f; excess=%.4f; previous_vy=%s;"
                                        + " gravity=%.3f",
                                mode,
                                m.dy,
                                limit - tol,
                                excess,
                                Double.isNaN(d.predVy)
                                        ? "-"
                                        : String.format(Locale.US, "%.4f", d.predVy),
                                g);
                reject = true;
            }

            if (!vyFromImpulse && (!vyOverride || takeoff)) {
                if (!vyOverride && !reject && !takeoff && merged != null) {
                    carryVy = Math.min(carryVy, merged[2]);
                    if (m.dy < merged[3] - tol && m.geo.ceiling()) {
                        carryVy = Math.max(carryVy, restartCarry(m.dy, g));
                    }
                }
            } else {
                carryVy = m.dy;
            }
        }

        if (!reject && judged && s.stepEnabled() && prevGround && landed && m.dy > 0.0) {
            double step =
                    Double.isFinite(m.stepHeightAttr) && m.stepHeightAttr >= 0.0
                            ? m.stepHeightAttr
                            : 0.6;
            double allowance = Math.max(step, jump) + 0.02;
            if (m.dy > allowance) {
                double impulse = openImpulseVy(d, m, now);
                if (!Double.isNaN(impulse) && impulse > 0.0) {
                    allowance += impulse;
                }
            }

            if (m.dy > allowance
                    && now - d.lastBounceSurface > 1500L
                    && !this.explainedByWorld(p, d, m, now)) {
                excess = m.dy - allowance;
                check = "Step A";
                details =
                        String.format(
                                Locale.US,
                                "dy=%.4f; max_step=%.3f; step_height=%.2f; jump=%.3f",
                                m.dy,
                                allowance,
                                step,
                                jump);
                reject = true;
            }
        }

        double carryH = m.horizontal;
        boolean usedImpulseH = false;
        double limitHNoImpulse = Double.NaN;
        boolean speedActive = s.speedPredictionEnabled() && c.speedEnabled();
        if (!reject && judged && !Double.isNaN(d.predH)) {
            boolean jumped = startGround && m.dy > 0.0;
            double accel = startGround ? attr : 0.026F;
            double carried = d.predH * friction;
            boolean mergeable = d.predHObserved <= 0.2 || carried - accel <= 0.0625;
            double mergeSlack = mergeable ? 0.0625 : 0.0;
            double tightH =
                    horizontalBound(carried, accel, attr, jumped && sprintPossible, mergeable);
            double tolH = s.speedPredictionTolerance();
            boolean itemSlow =
                    s.noSlowEnabled()
                            && m.usingItem
                            && d.predUsingItem
                            && m.itemUseTicks >= 3
                            && m.itemUseRemaining > 3
                            && m.itemUseSpeed < 0.85
                            && startGround
                            && groundNow
                            && !jumped;
            double sneakSpeed = sneakSpeed(m);
            boolean sneakSlow =
                    s.noSlowEnabled()
                            && m.sneaking
                            && d.predSneaking
                            && sneakSpeed < 0.85
                            && startGround
                            && groundNow
                            && !jumped;
            boolean noSlow = itemSlow || sneakSlow;
            double slowFactor = (itemSlow ? m.itemUseSpeed : 1.0) * (sneakSlow ? sneakSpeed : 1.0);
            double tightSlow =
                    noSlow
                            ? horizontalBound(
                                    carried, attr * slowFactor, attr * slowFactor, false, mergeable)
                            : Double.NaN;
            double limitH = tightH + tolH;
            if (m.horizontal > limitH && !jumped && m.dy > 0.0 && m.geo.fromGrounded()) {
                jumped = true;
                tightH =
                        Math.max(
                                tightH,
                                horizontalBound(carried, attr, attr, sprintPossible, mergeable));
                limitH = tightH + tolH;
            }

            limitHNoImpulse = limitH;
            boolean hFromImpulse = false;
            if (m.horizontal > limitH
                    || noSlow && m.horizontal > Math.max(tightSlow + tolH, 0.13)) {
                double impulse = openImpulseH(d, m, now);
                if (impulse > 0.0) {
                    limitH += impulse;
                    if (noSlow) {
                        tightSlow += impulse;
                    }

                    hFromImpulse = true;
                }
            }

            if (speedActive && m.horizontal > 0.2 && m.horizontal > limitH) {
                limitH += this.pushAllowance(p, m);
                if (m.horizontal > limitH && !this.explainedByWorld(p, d, m, now)) {
                    excess = m.horizontal - limitH;
                    check = "Speed B";
                    details =
                            String.format(
                                    Locale.US,
                                    "horizontal=%.4f; max=%.4f; excess=%.4f; carried=%.4f;"
                                            + " friction=%.3f; accel=%.4f; ground=%s",
                                    m.horizontal,
                                    limitH - tolH,
                                    excess,
                                    d.predH,
                                    friction,
                                    accel,
                                    startGround);
                    reject = true;
                }
            }

            if (!reject && noSlow) {
                double limitSlow = Math.max(tightSlow + tolH, 0.13);
                if (m.horizontal > limitSlow) {
                    limitSlow += this.pushAllowance(p, m);
                }
                if (m.horizontal > limitSlow) {
                    if (++d.noSlowMoves >= 2 && !this.explainedByWorld(p, d, m, now)) {
                        excess = m.horizontal - limitSlow;
                        check = itemSlow ? "NoSlow A" : "NoSlow B";
                        details =
                                String.format(
                                        Locale.US,
                                        "horizontal=%.4f; max=%.4f; item_speed=%s;"
                                                + " sneak_speed=%s; use_ticks=%d",
                                        m.horizontal,
                                        limitSlow,
                                        itemSlow
                                                ? String.format(Locale.US, "%.2f", m.itemUseSpeed)
                                                : "-",
                                        sneakSlow
                                                ? String.format(Locale.US, "%.2f", sneakSpeed)
                                                : "-",
                                        m.itemUseTicks);
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
                // A tolerated slowed move (first strike) may really be faster than the slow bound,
                // e.g. knockback momentum when landing while sneaking: carry what was observed,
                // never more than the normal-speed bound.
                carryH =
                        Math.min(
                                m.horizontal + mergeSlack,
                                noSlow
                                        ? Math.max(
                                                Math.max(tightSlow, 0.0),
                                                Math.min(m.horizontal, tightH))
                                        : tightH);
            }
        }

        if (!reject && (vyFromImpulse || usedImpulseH)) {
            double[] bases =
                    takeoff
                            ? new double[] {-g * 0.98F, jump}
                            : new double[] {merged != null ? merged[3] : m.dy};
            double[] maxH = new double[1];
            double impulseCarry =
                    this.consumeImpulse(
                            d,
                            m,
                            now,
                            vyFromImpulse ? impulseTightVy : Double.NaN,
                            s.flyPredictionTolerance(),
                            usedImpulseH ? limitHNoImpulse : Double.NaN,
                            bases,
                            maxH);
            if (!Double.isNaN(impulseCarry)) {
                if (!landed) {
                    double observed = Double.isNaN(carryVy) ? m.dy : carryVy;
                    carryVy = Math.max(observed, impulseCarry);
                    if (carryVy > observed) {
                        carryVyLow = observed;
                    }
                }

                double hBase = Double.isNaN(limitHNoImpulse) ? m.horizontal : limitHNoImpulse;
                carryH = Math.max(carryH, hBase + maxH[0]);
                d.predImpulseAppliedAt = now;
            }
        }

        if (reject) {
            reject = this.act(p, d, m, check, details, excess, now);
        } else if (judged) {
            d.flyPredBuffer = Math.max(0.0, d.flyPredBuffer - 0.02);
            d.speedPredBuffer = Math.max(0.0, d.speedPredBuffer - 0.05);
            d.stepBuffer = Math.max(0.0, d.stepBuffer - 0.02);
            d.noSlowBuffer = Math.max(0.0, d.noSlowBuffer - 0.05);
        }

        if (!reject && !rejectedAlready) {
            d.predVy = carryVy;
            d.predVyLow = carryVyLow;
            d.predH = carryH;
            d.predHObserved = m.horizontal;
            d.predPrevGround = startGround;
            d.predLastGroundStrict = groundNow;
            d.predUsingItem = m.usingItem;
            d.predSneaking = m.sneaking;
        } else {
            d.predVy = 0.0;
            d.predVyLow = Double.NaN;
            d.predH = 0.0;
            d.predHObserved = 0.0;
            d.predPrevGround = startGround;
            d.predUsingItem = m.usingItem;
            d.predSneaking = m.sneaking;
            d.noSlowMoves = 0;
        }

        d.predLastAt = now;
        return reject;
    }

    private boolean act(
            Player p,
            PlayerData d,
            MoveContext m,
            String check,
            String details,
            double excess,
            long now) {
        CheckConfig c = this.cfg;
        StrictConfig s = this.strict;
        double flagAt;
        boolean setbackAllowed;
        switch (check) {
            case "Fly B":
                d.flyPredBuffer = d.flyPredBuffer + 1.0 + Math.min(2.0, excess * 4.0);
                if (now - d.flyPredLastViolation > 1000L) {
                    d.flyPredStrikes = 0;
                }

                d.flyPredStrikes++;
                d.flyPredLastViolation = now;
                flagAt = s.flyPredictionFlagAt();
                setbackAllowed = c.flySetback();
                break;
            case "Step A":
                d.stepBuffer++;
                flagAt = s.stepFlagAt();
                setbackAllowed = c.flySetback();
                break;
            case "NoSlow A":
            case "NoSlow B":
                d.noSlowBuffer++;
                flagAt = s.noSlowFlagAt();
                setbackAllowed = c.speedSetback();
                break;
            default:
                d.speedPredBuffer = d.speedPredBuffer + 1.0 + Math.min(1.0, excess * 2.0);
                if (now - d.speedPredLastViolation > 1000L) {
                    d.speedPredStrikes = 0;
                }

                d.speedPredStrikes++;
                d.speedPredLastViolation = now;
                flagAt = s.speedPredictionFlagAt();
                setbackAllowed = c.speedSetback();
        }
        double buffer =
                switch (check) {
                    case "Fly B" -> d.flyPredBuffer;
                    case "Step A" -> d.stepBuffer;
                    case "NoSlow A", "NoSlow B" -> d.noSlowBuffer;
                    default -> d.speedPredBuffer;
                };
        boolean prevent =
                setbackAllowed
                        && this.prevention.movement()
                        && (this.prevention.instant() || buffer >= flagAt);
        boolean toGround =
                prevent && "Fly B".equals(check) && (d.flyPredStrikes >= 2 || excess > 0.15);
        boolean grounded = false;
        if (toGround) {
            grounded = this.setback.setback(p, d, null);
        }

        if (buffer >= flagAt) {
            String id =
                    this.sink.flag(
                            p,
                            d,
                            check,
                            reason(check),
                            details
                                    + String.format(
                                            Locale.US,
                                            "; buffer=%.1f; strikes=%d",
                                            buffer,
                                            "Fly B".equals(check)
                                                    ? d.flyPredStrikes
                                                    : d.speedPredStrikes));
            if (id != null && prevent) {
                this.sink.action(id, grounded ? "Setback to validated ground" : "Move rejected");
            }

            switch (check) {
                case "Fly B":
                    d.flyPredBuffer = 1.0;
                    break;
                case "Step A":
                    d.stepBuffer = 1.0;
                    break;
                case "NoSlow A":
                case "NoSlow B":
                    d.noSlowBuffer = 1.0;
                    break;
                default:
                    d.speedPredBuffer = 1.0;
            }
        }

        return prevent;
    }

    static String reason(String check) {
        return switch (check) {
            case "Fly B" -> "Vertical motion that gravity does not allow";
            case "Step A" -> "Stepped higher than the step height allows";
            case "NoSlow A" -> "Not slowed down while using an item";
            case "NoSlow B" -> "Not slowed down while sneaking";
            default -> "Faster than the carried momentum allows";
        };
    }

    static double horizontalBound(
            double carried, double accel, double groundAccel, boolean jumped, boolean mergeable) {
        double jump = jumped ? 0.2 : 0.0;
        double single = carried + accel + jump;
        return !mergeable
                ? single
                : Math.max(single, 0.11937500163912773 + Math.max(accel, groundAccel) + jump);
    }

    private boolean judgeable(PlayerData d, MoveContext m, String exemptReason, long now) {
        return exemptReason != null && !IMPULSE_REASONS.contains(exemptReason)
                ? false
                : this.judgeableBase(d, m, now);
    }

    private boolean judgeableBase(PlayerData d, MoveContext m, long now) {
        if (m.gameMode == GameMode.CREATIVE || m.gameMode == GameMode.SPECTATOR) {
            return false;
        } else if (!m.allowFlight
                && !m.flying
                && !m.gliding
                && !m.insideVehicle
                && !m.riptiding
                && !m.swimming
                && !m.levitation
                && !m.slowFalling) {
            int lag = Math.min(1000, Math.max(0, m.ping));
            return now - d.lastGhostBlock < 1500L + (long) lag
                    ? false
                    : !m.lungeWeapon || now - d.lastSwingAt >= 800L + (long) lag;
        } else {
            return false;
        }
    }

    private boolean explainedByWorld(Player p, PlayerData d, MoveContext m, long now) {
        if (now - d.lastBounceSurface < 1500L) {
            return true;
        } else if (m.geo.movingBlockNearby()) {
            return true;
        } else {
            try {
                return p != null && this.platformNearby.test(p);
            } catch (Throwable ignored) {
                return true;
            }
        }
    }

    private double pushAllowance(Player p, MoveContext m) {
        if (p == null) {
            return 0.0;
        } else {
            try {
                int pushers = 0;

                for (Entity e : p.getNearbyEntities(0.8, 1.0, 0.8)) {
                    if (e instanceof LivingEntity) {
                        pushers++;
                    }

                    if (pushers >= 4) {
                        break;
                    }
                }

                return 0.08 * (double) pushers;
            } catch (Throwable ignored) {
                return 0.3;
            }
        }
    }

    /** Food level at or below which a vanilla client can neither start nor keep sprinting. */
    static final int SPRINT_FOOD_LEVEL = 6;

    /**
     * Whether the client may be sprinting. A vanilla client only sprints with more than 6 food (or
     * flight allowed) and reports every sprint start to the server, which KYRO refuses while the
     * player is too hungry. So a hungry player the server sees as not sprinting cannot be
     * sprinting; the usual "maybe sprinting" allowance would otherwise let an omni/hunger sprint
     * cheat move 30 % faster. Server-side sprinting (started before food dropped) stays allowed,
     * and the allowance is held for 600 ms + ping after the last moment sprinting was possible.
     */
    static boolean sprintPossible(PlayerData d, MoveContext m, long now) {
        boolean hungry = m.foodLevel <= SPRINT_FOOD_LEVEL && !m.allowFlight;
        if (!hungry || m.sprinting) {
            d.lastSprintPossibleAt = now;
            return true;
        }
        long hold = Math.min(3000L, 600L + (long) Math.max(0, m.ping));
        return d.lastSprintPossibleAt != 0L && now - d.lastSprintPossibleAt <= hold;
    }

    /** Sneaking input multiplier: the sneaking_speed attribute, vanilla default 0.3. */
    static double sneakSpeed(MoveContext m) {
        double v = m.sneakingSpeedAttr;
        return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.3;
    }

    double speedAttribute(PlayerData d, MoveContext m, long now) {
        return this.speedAttribute(d, m, now, true);
    }

    double speedAttribute(PlayerData d, MoveContext m, long now, boolean sprintPossible) {
        double attr = m.movementSpeedAttr;
        if (!Double.isFinite(attr) || attr <= 0.0) {
            attr = 0.1 * Math.max(0.0, (double) m.walkSpeed) / 0.2;
            if (m.speedAmplifier >= 0) {
                attr *= 1.0 + 0.2 * (double) (m.speedAmplifier + 1);
            }

            if (m.sprinting) {
                attr *= 1.3;
            }
        }

        if (!m.sprinting && sprintPossible) {
            // Sprint state can lag one move behind the input; allow sprint speed when possible.
            attr *= 1.3;
        }

        long hold = Math.min(3000L, 600L + (long) Math.max(0, m.ping));
        // Hold the highest recent value for 600 ms + ping after the LAST drop: the client applies
        // a lowered speed only after its ping, and several drops can follow each other (Speed
        // ending, sprint stopping, hunger) - each one needs its own hold.
        if (Double.isNaN(d.predAttrPeak) || attr >= d.predAttrPeak) {
            d.predAttrPeak = attr;
            d.predAttrPeakAt = 0L;
        } else if (d.predAttrPeakAt == 0L || attr < d.predAttrLast - 1.0E-9) {
            d.predAttrPeakAt = now;
        } else if (now - d.predAttrPeakAt > hold) {
            d.predAttrPeak = attr;
            d.predAttrPeakAt = 0L;
        }
        d.predAttrLast = attr;

        return Math.max(attr, d.predAttrPeak);
    }

    static long impulseWindow(MoveContext m) {
        return Math.min(4000L, 1000L + 2L * (long) Math.max(0, m.ping));
    }

    static double openImpulseVy(PlayerData d, MoveContext m, long now) {
        long window = impulseWindow(m);
        double best = Double.NaN;

        for (int i = 0; i < 4; i++) {
            if (!d.impulseUsedRing[i]
                    && now >= d.impulseAtRing[i]
                    && now - d.impulseAtRing[i] <= window
                    && (Double.isNaN(best) || d.impulseVyRing[i] > best)) {
                best = d.impulseVyRing[i];
            }
        }

        return best;
    }

    static double openImpulseH(PlayerData d, MoveContext m, long now) {
        long window = impulseWindow(m);
        double best = 0.0;

        for (int i = 0; i < 4; i++) {
            if (!d.impulseUsedRing[i]
                    && now >= d.impulseAtRing[i]
                    && now - d.impulseAtRing[i] <= window) {
                best = Math.max(best, d.impulseHRing[i]);
            }
        }

        return best;
    }

    static double withImpulse(double predicted, double impulse) {
        return Math.max(predicted, impulse) + Math.max(0.0, Math.min(predicted, impulse));
    }

    static double restartCarry(double actual, double gravity) {
        double v = 0.0;
        double sum = 0.0;
        double best = Double.NEGATIVE_INFINITY;
        double bestErr = Double.POSITIVE_INFINITY;

        for (int k = 1; k <= 12; k++) {
            v = (v - gravity) * 0.98F;
            sum += v;
            double err = Math.abs(sum - actual);
            if (err < bestErr) {
                bestErr = err;
                best = v;
            }

            if (sum < actual - 0.0625) {
                break;
            }
        }

        return best;
    }

    static double takeoffCarry(double dy, double jump, double gravity) {
        return dy > 0.0 ? Math.max(dy, jump) : Math.max(dy, -gravity * 0.98F);
    }

    double consumeImpulse(
            PlayerData d,
            MoveContext m,
            long now,
            double tightVy,
            double tol,
            double limitH,
            double[] bases,
            double[] maxH) {
        long window = impulseWindow(m);
        int oldest = -1;
        double carry = Double.NaN;
        maxH[0] = 0.0;

        for (int i = 0; i < 4; i++) {
            if (!d.impulseUsedRing[i]
                    && now >= d.impulseAtRing[i]
                    && now - d.impulseAtRing[i] <= window) {
                double vy = d.impulseVyRing[i];
                boolean explains =
                        (Double.isNaN(tightVy) || m.dy <= withImpulse(tightVy, vy) + tol)
                                && (Double.isNaN(limitH)
                                        || m.horizontal <= limitH + d.impulseHRing[i]);
                if (explains) {
                    if (oldest < 0 || d.impulseAtRing[i] < d.impulseAtRing[oldest]) {
                        oldest = i;
                    }

                    for (double b : bases) {
                        if (Double.isFinite(b)) {
                            double v = withImpulse(b, vy);
                            if (Double.isNaN(carry) || v > carry) {
                                carry = v;
                            }
                        }
                    }

                    maxH[0] = Math.max(maxH[0], d.impulseHRing[i]);
                }
            }
        }

        if (oldest >= 0) {
            d.impulseUsedRing[oldest] = true;
        }

        return carry;
    }

    static double[] merged(double v0, double gravity, double actual) {
        double v = v0;
        double sum = 0.0;
        double best = Double.NEGATIVE_INFINITY;
        double v1 = Double.NaN;
        double bestErr = Double.POSITIVE_INFINITY;
        double mergedCarry = Double.NEGATIVE_INFINITY;
        double mergedClean = Double.NEGATIVE_INFINITY;

        for (int k = 1; k <= 12; k++) {
            v = (v - gravity) * 0.98F;
            if (Math.abs(v) < 0.003) {
                v = 0.0;
            }

            sum += v;
            if (sum > best) {
                best = sum;
            }

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

            if (Math.abs(sum) > 0.0625) {
                break;
            }
        }

        double singleClean = Math.min(actual, v1);
        double carry = Math.max(actual, mergedCarry);
        double clean = Math.max(singleClean, mergedClean);
        return new double[] {best, carry, clean, v1};
    }

    public void configure(CheckConfig cfg, StrictConfig strict) {
        this.cfg = cfg;
        this.strict = strict;
    }
}
