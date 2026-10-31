package de.kyrosmp.anticheat.checks.combat;

import de.kyrosmp.anticheat.core.AttributeSet;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.CheckDiagnostics;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerHistory;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;

import io.papermc.paper.raytracing.BlockCollisionMode;
import io.papermc.paper.raytracing.RayTraceTarget;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

public final class CombatChecks {
    private static final double HITBOX_MARGIN = 0.1;
    private static final long INTERPOLATION_MS = 150L;
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    private PreventionConfig prevention = PreventionConfig.defaults();
    private volatile StrictConfig strict = StrictConfig.defaults();
    private final Attribute interactionRangeAttribute;
    volatile Predicate<Location> regionOwned = Bukkit::isOwnedByCurrentRegion;
    static final int WALL_MAX_PING = 400;

    public CombatChecks withPrevention(PreventionConfig policy) {
        this.prevention = policy;
        return this;
    }

    public CombatChecks withStrict(StrictConfig strict) {
        this.strict = strict;
        return this;
    }

    public CombatChecks(FlagSink sink, CheckConfig cfg, AttributeSet attributes) {
        this.sink = sink;
        this.cfg = cfg;
        this.interactionRangeAttribute = attributes.entityInteractionRange();
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public CombatChecks.Result onAttack(
            Player p, PlayerData d, LivingEntity target, PlayerData targetData, long now) {
        CheckConfig c = this.cfg;
        boolean cancel = false;
        Vector eye = null;
        Vector closest = null;

        try {
            Location eyeLoc = p.getEyeLocation();
            eye = eyeLoc.toVector();
            int ping = Punisher.safePing(p);
            double range = AttributeSet.value(p, this.interactionRangeAttribute);
            if (Double.isNaN(range)) {
                range = 3.0;
            }

            double margin = 0.1;
            double[] attackRange = ItemReach.mainHand(p);
            if (attackRange != null) {
                range = Math.max(range, attackRange[0]);
                margin += Math.max(0.0, attackRange[1]);
            }

            long window = Math.min(600L, (long) ping + 150L + 50L);
            BoundingBox box = closestBox(target, eye).clone().expand(margin);
            closest =
                    new Vector(
                            clamp(eye.getX(), box.getMinX(), box.getMaxX()),
                            clamp(eye.getY(), box.getMinY(), box.getMaxY()),
                            clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()));
            double distance = eye.distance(closest);
            String compensation = "none";
            if (targetData != null) {
                BoundingBox current = target.getBoundingBox();
                double hist =
                        targetData.history.minHitboxDistance(
                                now - window,
                                eye.getX(),
                                eye.getY(),
                                eye.getZ(),
                                current.getWidthX() / 2.0,
                                current.getHeight(),
                                margin);
                if (hist < distance) {
                    distance = hist;
                    compensation = "history";
                }
            } else {
                Vector v = target.getVelocity();
                Mob mob;
                boolean ai = !(target instanceof Mob) || (mob = (Mob) target).hasAI();
                double vh = Math.hypot(v.getX(), v.getZ());
                if (ai && vh > 0.01) {
                    double shift =
                            Math.min(0.6, vh * Math.min(2.0, Math.ceil((double) window / 50.0)));
                    BoundingBox past =
                            box.clone().shift(-v.getX() / vh * shift, 0.0, -v.getZ() / vh * shift);
                    double dp =
                            PlayerHistory.boxDistance(
                                    eye.getX(),
                                    eye.getY(),
                                    eye.getZ(),
                                    past.getMinX(),
                                    past.getMinY(),
                                    past.getMinZ(),
                                    past.getMaxX(),
                                    past.getMaxY(),
                                    past.getMaxZ());
                    if (dp < distance) {
                        distance = dp;
                        compensation = "velocity";
                    }
                }
            }

            double limit = range + c.reachTolerance() + Math.min(0.3, (double) ping * 0.001);
            d.history.addAttack(
                    new PlayerHistory.AttackSample(now, target.getUniqueId(), distance));
            if (c.reachEnabled()) {
                d.reachBuffer =
                        distance > limit
                                ? d.reachBuffer + (distance > limit + 0.4 ? 1.2 : 0.7)
                                : Math.max(0.0, d.reachBuffer - 0.6);
                boolean extreme = distance > limit + 1.2;
                boolean cancelReach =
                        (extreme
                                        || d.reachBuffer >= 3.0
                                        || this.strict.reachInstantCancel() && distance > limit)
                                && c.reachCancel();
                cancel |= cancelReach;
                if (d.reachBuffer >= 4.0 || extreme) {
                    String id =
                            this.sink.flag(
                                    p,
                                    d,
                                    "Reach A",
                                    "Hit beyond possible reach",
                                    String.format(
                                            Locale.US,
                                            "distance=%.2f; limit=%.2f; range=%.2f;"
                                                    + " compensation=%s(%dms); ping=%d; target=%s",
                                            distance,
                                            limit,
                                            range,
                                            compensation,
                                            window,
                                            ping,
                                            target.getType()));
                    if (id != null && cancelReach) {
                        this.sink.action(id, "Hit cancelled");
                    }

                    d.reachBuffer = 1.0;
                }
            }

            if (!cancel && c.killAuraEnabled() && c.wallHitEnabled()) {
                cancel |=
                        this.wallHit(p, d, target, targetData, eyeLoc, box, closest, ping, c, now);
            }

            if (c.killAuraEnabled() && distance > 1.2) {
                Vector toTarget = closest.clone().subtract(eye);
                double angle = Math.toDegrees((double) eyeLoc.getDirection().angle(toTarget));
                d.angleBuffer =
                        angle > c.killAuraMaxAngle()
                                ? ++d.angleBuffer
                                : Math.max(0.0, d.angleBuffer - 0.5);
                boolean cancelAngle = d.angleBuffer >= 2.0 && c.killAuraCancel();
                cancel |= cancelAngle;
                if (d.angleBuffer >= 4.0) {
                    String id =
                            this.sink.flag(
                                    p,
                                    d,
                                    "KillAura A",
                                    "Hit outside field of view",
                                    String.format(
                                            Locale.US,
                                            "angle=%.0f; max=%.0f; distance=%.2f",
                                            angle,
                                            c.killAuraMaxAngle(),
                                            distance));
                    if (id != null && cancelAngle) {
                        this.sink.action(id, "Hit cancelled");
                    }

                    d.angleBuffer = 1.0;
                }
            }
        } catch (Throwable ex) {
            CheckDiagnostics.report(CheckDiagnostics.Context.COMBAT_ATTACK, ex);
        }

        cancel |= this.switches(p, d, target.getUniqueId(), eye, target, now, c);
        d.lastTarget = target.getUniqueId();
        d.lastAttack = now;
        return new CombatChecks.Result(this.prevention.actions() && cancel);
    }

    private boolean wallHit(
            Player p,
            PlayerData d,
            LivingEntity target,
            PlayerData targetData,
            Location eyeLoc,
            BoundingBox box,
            Vector closest,
            int ping,
            CheckConfig c,
            long now) {
        try {
            if (ping > 400) {
                return false;
            } else {
                Location tl = target.getLocation();
                if (this.regionOwned.test(tl) && this.regionOwned.test(eyeLoc)) {
                    World w = eyeLoc.getWorld();
                    boolean blocked = boxHidden(w, eyeLoc, box);
                    if (blocked) {
                        long window = (long) Math.min(600, ping + 200);
                        if (targetData != null) {
                            double hw = box.getWidthX() / 2.0;
                            double hgt = box.getHeight();
                            double cx = box.getCenterX();
                            double cz = box.getCenterZ();
                            double margin =
                                    Math.max(0.0, hw - target.getBoundingBox().getWidthX() / 2.0);
                            int checked = 0;

                            for (PlayerHistory.MoveSample m : targetData.history.moves()) {
                                if (m.time() >= now - window
                                        && checked < 12
                                        && (!(Math.abs(m.x() - cx) < 0.05)
                                                || !(Math.abs(m.z() - cz) < 0.05))) {
                                    BoundingBox past =
                                            new BoundingBox(
                                                    m.x() - hw,
                                                    m.y() - margin,
                                                    m.z() - hw,
                                                    m.x() + hw,
                                                    m.y() - margin + hgt,
                                                    m.z() + hw);
                                    checked++;
                                    if (!boxHidden(w, eyeLoc, past)) {
                                        blocked = false;
                                        break;
                                    }
                                }
                            }
                        } else {
                            Vector v = target.getVelocity();
                            Mob mob;
                            boolean ai = !(target instanceof Mob) || (mob = (Mob) target).hasAI();
                            double vh = Math.hypot(v.getX(), v.getZ());
                            if (ai && vh > 0.01) {
                                double total =
                                        Math.min(
                                                0.6,
                                                vh
                                                        * Math.min(
                                                                2.0,
                                                                Math.ceil((double) window / 50.0)));

                                for (int i = 1; i <= 3 && blocked; i++) {
                                    double sft = total * (double) i / 3.0;
                                    blocked =
                                            boxHidden(
                                                    w,
                                                    eyeLoc,
                                                    box.clone()
                                                            .shift(
                                                                    -v.getX() / vh * sft,
                                                                    0.0,
                                                                    -v.getZ() / vh * sft));
                                }
                            }
                        }
                    }

                    if (!blocked) {
                        d.wallHitBuffer = Math.max(0.0, d.wallHitBuffer - 1.0);
                        return false;
                    } else {
                        d.wallHitBuffer++;
                        boolean cancel = d.wallHitBuffer >= 2.0 && c.killAuraCancel();
                        if (d.wallHitBuffer >= c.wallHitFlagAt()) {
                            String id =
                                    this.sink.flag(
                                            p,
                                            d,
                                            "KillAura C",
                                            "Hit through solid blocks",
                                            String.format(
                                                    Locale.US,
                                                    "every line of sight blocked for all target"
                                                            + " positions of the last %dms;"
                                                            + " buffer=%.1f; ping=%d; target=%s",
                                                    Math.min(600, ping + 200),
                                                    d.wallHitBuffer,
                                                    ping,
                                                    target.getType()));
                            if (id != null && cancel) {
                                this.sink.action(id, "Hit cancelled");
                            }

                            d.wallHitBuffer = 1.0;
                        }

                        return cancel;
                    }
                } else {
                    return false;
                }
            }
        } catch (Throwable ex) {
            CheckDiagnostics.report(CheckDiagnostics.Context.WALL_HIT, ex);
            return false;
        }
    }

    static boolean boxHidden(World w, Location eyeLoc, BoundingBox box) {
        Vector eye = eyeLoc.toVector();
        Vector closest =
                new Vector(
                        clamp(eye.getX(), box.getMinX(), box.getMaxX()),
                        clamp(eye.getY(), box.getMinY(), box.getMaxY()),
                        clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()));
        if (lineBlocked(w, eyeLoc, closest) && lineBlocked(w, eyeLoc, box.getCenter())) {
            BoundingBox in =
                    box.clone()
                            .expand(
                                    -Math.min(0.05, box.getWidthX() / 4.0),
                                    -0.05,
                                    -Math.min(0.05, box.getWidthZ() / 4.0));

            for (int i = 0; i < 8; i++) {
                Vector corner =
                        new Vector(
                                (i & 1) == 0 ? in.getMinX() : in.getMaxX(),
                                (i & 2) == 0 ? in.getMinY() : in.getMaxY(),
                                (i & 4) == 0 ? in.getMinZ() : in.getMaxZ());
                if (!lineBlocked(w, eyeLoc, corner)) {
                    return false;
                }
            }

            return true;
        } else {
            return false;
        }
    }

    static boolean lineBlocked(World w, Location eye, Vector to) {
        Vector dir = to.clone().subtract(eye.toVector());
        double len = dir.length();
        if (len < 0.3) {
            return false;
        } else {
            dir.multiply(1.0 / len);
            return hits(w, eye, dir, len - 0.05, BlockCollisionMode.COLLIDER)
                    && hits(w, eye, dir, len - 0.05, BlockCollisionMode.OUTLINE);
        }
    }

    private static boolean hits(
            World w, Location eye, Vector dir, double max, BlockCollisionMode mode) {
        RayTraceResult r =
                w.rayTrace(
                        b ->
                                b.start(eye)
                                        .direction(dir)
                                        .maxDistance(max)
                                        .fluidCollisionMode(FluidCollisionMode.NEVER)
                                        .blockCollisionMode(mode)
                                        .targets(RayTraceTarget.BLOCK, new RayTraceTarget[0]));
        return r != null && r.getHitBlock() != null;
    }

    private boolean switches(
            Player p,
            PlayerData d,
            UUID targetId,
            Vector eye,
            LivingEntity target,
            long now,
            CheckConfig c) {
        boolean cancel = false;
        Vector dir = null;

        try {
            if (eye != null) {
                BoundingBox b = target.getBoundingBox();
                dir =
                        new Vector(
                                b.getCenterX() - eye.getX(),
                                b.getCenterY() - eye.getY(),
                                b.getCenterZ() - eye.getZ());
                if (dir.lengthSquared() > 1.0E-6) {
                    dir.normalize();
                } else {
                    dir = null;
                }
            }
        } catch (Throwable ex) {
            CheckDiagnostics.report(CheckDiagnostics.Context.TARGET_SWITCH, ex);
            dir = null;
        }

        double separation;
        if (c.killAuraEnabled()
                && d.lastTarget != null
                && !d.lastTarget.equals(targetId)
                && now - d.lastAttack < c.switchMaxIntervalMs()
                && (separation = separationDegrees(d, dir)) >= c.switchMinAngle()) {
            if (now - d.switchWindowStart > c.switchWindowMs()) {
                d.switchWindowStart = now;
                d.fastTargetSwitches = 0;
            }

            d.fastTargetSwitches++;
            if (d.fastTargetSwitches >= c.switchMaxSwitches()) {
                if (c.killAuraCancel()) {
                    cancel = true;
                }

                String id;
                if ((id =
                                        this.sink.flag(
                                                p,
                                                d,
                                                "KillAura B",
                                                "Very fast switching between widely separated"
                                                        + " targets",
                                                String.format(
                                                        Locale.US,
                                                        "rapid target switches=%d in <%dms;"
                                                            + " last_separation=%.0f°; min=%.0f°",
                                                        d.fastTargetSwitches,
                                                        c.switchWindowMs(),
                                                        separation,
                                                        c.switchMinAngle())))
                                != null
                        && c.killAuraCancel()) {
                    this.sink.action(id, "Hit cancelled");
                }

                d.fastTargetSwitches = 1;
                d.switchWindowStart = now;
            }
        }

        if (dir != null) {
            d.lastAttackDirX = dir.getX();
            d.lastAttackDirY = dir.getY();
            d.lastAttackDirZ = dir.getZ();
        } else {
            d.lastAttackDirX = 0.0;
            d.lastAttackDirY = 0.0;
            d.lastAttackDirZ = 0.0;
        }

        return cancel;
    }

    static double separationDegrees(PlayerData d, Vector dir) {
        if (dir == null) {
            return 0.0;
        } else {
            double lx = d.lastAttackDirX;
            double ly = d.lastAttackDirY;
            double lz = d.lastAttackDirZ;
            double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (len < 1.0E-6) {
                return 0.0;
            } else {
                double cos = (lx * dir.getX() + ly * dir.getY() + lz * dir.getZ()) / len;
                return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
            }
        }
    }

    private static BoundingBox closestBox(LivingEntity target, Vector eye) {
        BoundingBox best = target.getBoundingBox();
        if (target instanceof ComplexLivingEntity complex) {
            double bestDist = distance(eye, best);

            for (ComplexEntityPart part : complex.getParts()) {
                BoundingBox b = part.getBoundingBox();
                double dist = distance(eye, b);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = b;
                }
            }
        }

        return best;
    }

    private static double distance(Vector eye, BoundingBox b) {
        return PlayerHistory.boxDistance(
                eye.getX(),
                eye.getY(),
                eye.getZ(),
                b.getMinX(),
                b.getMinY(),
                b.getMinZ(),
                b.getMaxX(),
                b.getMaxY(),
                b.getMaxZ());
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    public static record Result(boolean cancel) {}
}
