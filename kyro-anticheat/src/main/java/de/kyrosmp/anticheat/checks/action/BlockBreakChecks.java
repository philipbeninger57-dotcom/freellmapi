package de.kyrosmp.anticheat.checks.action;

import de.kyrosmp.anticheat.core.CheckDiagnostics;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;

import io.papermc.paper.raytracing.BlockCollisionMode;
import io.papermc.paper.raytracing.RayTraceTarget;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.function.BiPredicate;

public final class BlockBreakChecks {
    private final FlagSink sink;
    private volatile StrictConfig strict;
    private volatile PreventionConfig prevention = PreventionConfig.defaults();
    volatile BiPredicate<Player, Block> hidden = BlockBreakChecks::hiddenFromEye;

    public BlockBreakChecks(FlagSink sink, StrictConfig strict) {
        this.sink = sink;
        this.strict = strict;
    }

    public BlockBreakChecks withPrevention(PreventionConfig policy) {
        this.prevention = policy;
        return this;
    }

    public void configure(StrictConfig strict) {
        this.strict = strict;
    }

    public boolean onDigStart(Player p, PlayerData d, Block block, long now) {
        StrictConfig s = this.strict;
        GameMode gm = p.getGameMode();
        if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) {
            boolean cancel = false;
            if (s.nukerEnabled()) {
                cancel = this.nuker(p, d, now, s);
            }

            if (!cancel && s.ghostHandEnabled()) {
                cancel = this.ghostHand(p, d, block, s);
            }

            return cancel && this.prevention.actions();
        } else {
            return false;
        }
    }

    private boolean nuker(Player p, PlayerData d, long now, StrictConfig s) {
        d.digTimes.addLast(now);

        while (!d.digTimes.isEmpty()
                && (now - d.digTimes.peekFirst() >= 1000L || now < d.digTimes.peekFirst())) {
            d.digTimes.pollFirst();
        }

        int limit = s.nukerMaxPerSecond();

        while (d.digTimes.size() > limit + 1) {
            d.digTimes.pollFirst();
        }

        if (d.digTimes.size() <= limit) {
            d.nukerBuffer = Math.max(0.0, d.nukerBuffer - 0.05);
            return false;
        } else {
            d.nukerBuffer++;
            if (d.nukerBuffer >= s.nukerFlagAt()) {
                String id =
                        this.sink.flag(
                                p,
                                d,
                                "Nuker A",
                                "More blocks started per second than a client can dig",
                                String.format(
                                        Locale.US,
                                        "digs_per_second>%d; buffer=%.1f",
                                        limit,
                                        d.nukerBuffer));
                if (id != null && this.prevention.actions()) {
                    this.sink.action(id, "Digging cancelled");
                }

                d.nukerBuffer = 1.0;
            }

            return true;
        }
    }

    private boolean ghostHand(Player p, PlayerData d, Block block, StrictConfig s) {
        boolean blocked;
        try {
            blocked = this.hidden.test(p, block);
        } catch (Throwable ex) {
            CheckDiagnostics.report(CheckDiagnostics.Context.GHOST_HAND, ex);
            blocked = false;
        }

        if (!blocked) {
            d.ghostHandBuffer = Math.max(0.0, d.ghostHandBuffer - 0.25);
            return false;
        } else {
            d.ghostHandBuffer++;
            if (d.ghostHandBuffer >= s.ghostHandFlagAt()) {
                String id =
                        this.sink.flag(
                                p,
                                d,
                                "GhostHand A",
                                "Digging a block that is completely hidden behind other blocks",
                                String.format(
                                        Locale.US,
                                        "block=%s %d/%d/%d; every line of sight blocked;"
                                                + " buffer=%.1f",
                                        block.getType(),
                                        block.getX(),
                                        block.getY(),
                                        block.getZ(),
                                        d.ghostHandBuffer));
                if (id != null && this.prevention.actions()) {
                    this.sink.action(id, "Digging cancelled");
                }

                d.ghostHandBuffer = 1.0;
            }

            return true;
        }
    }

    static boolean hiddenFromEye(Player p, Block block) {
        Location eye = p.getEyeLocation();
        World w = block.getWorld();
        if (eye.getWorld() != w) {
            return false;
        } else {
            Vector origin = eye.toVector();
            Block eyeBlock = w.getBlockAt(eye);
            BoundingBox eyeShape = eyeBlock.getBoundingBox();
            if (eyeShape.getVolume() > 0.0 && eyeShape.clone().expand(0.01).contains(origin)) {
                return false;
            } else {
                BoundingBox box = block.getBoundingBox();
                if (box.getVolume() <= 1.0E-6) {
                    box = BoundingBox.of(block);
                }

                if (box.clone().expand(0.05).contains(origin)) {
                    return false;
                } else {
                    double sx = Math.min(0.05, box.getWidthX() / 4.0);
                    double sy = Math.min(0.05, box.getHeight() / 4.0);
                    double sz = Math.min(0.05, box.getWidthZ() / 4.0);
                    BoundingBox in = box.clone().expand(-sx, -sy, -sz);
                    Vector[] points = new Vector[15];
                    int n = 0;
                    points[n++] = in.getCenter();
                    points[n++] =
                            new Vector(
                                    clamp(origin.getX(), in.getMinX(), in.getMaxX()),
                                    clamp(origin.getY(), in.getMinY(), in.getMaxY()),
                                    clamp(origin.getZ(), in.getMinZ(), in.getMaxZ()));

                    for (int i = 0; i < 8; i++) {
                        points[n++] =
                                new Vector(
                                        (i & 1) == 0 ? in.getMinX() : in.getMaxX(),
                                        (i & 2) == 0 ? in.getMinY() : in.getMaxY(),
                                        (i & 4) == 0 ? in.getMinZ() : in.getMaxZ());
                    }

                    Vector c = in.getCenter();
                    points[n++] = new Vector(in.getMinX(), c.getY(), c.getZ());
                    points[n++] = new Vector(in.getMaxX(), c.getY(), c.getZ());
                    points[n++] = new Vector(c.getX(), in.getMaxY(), c.getZ());
                    points[n++] = new Vector(c.getX(), c.getY(), in.getMinZ());
                    points[n++] = new Vector(c.getX(), c.getY(), in.getMaxZ());

                    for (int i = 0; i < n; i++) {
                        if (reaches(w, eye, origin, points[i], block)) {
                            return false;
                        }
                    }

                    return true;
                }
            }
        }
    }

    private static boolean reaches(
            World w, Location eye, Vector origin, Vector point, Block target) {
        Vector dir = point.clone().subtract(origin);
        double len = dir.length();
        if (len < 0.05) {
            return true;
        } else {
            dir.multiply(1.0 / len);
            RayTraceResult r =
                    w.rayTrace(
                            b ->
                                    b.start(eye)
                                            .direction(dir)
                                            .maxDistance(len + 0.01)
                                            .fluidCollisionMode(FluidCollisionMode.NEVER)
                                            .blockCollisionMode(BlockCollisionMode.OUTLINE)
                                            .targets(RayTraceTarget.BLOCK, new RayTraceTarget[0]));
            if (r != null && r.getHitBlock() != null) {
                Block hit = r.getHitBlock();
                return hit.getX() == target.getX()
                        && hit.getY() == target.getY()
                        && hit.getZ() == target.getZ();
            } else {
                return true;
            }
        }
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
