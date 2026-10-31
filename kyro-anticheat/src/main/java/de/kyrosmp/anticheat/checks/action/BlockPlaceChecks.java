package de.kyrosmp.anticheat.checks.action;

import de.kyrosmp.anticheat.core.AttributeSet;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerHistory;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;

public final class BlockPlaceChecks {
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    private PreventionConfig prevention = PreventionConfig.defaults();
    private volatile StrictConfig strict = StrictConfig.defaults();
    volatile ToDoubleBiFunction<Player, Block[]> faceMargin = BlockPlaceChecks::faceMargin;
    private final Attribute blockRange;
    volatile Predicate<Block> solid = b -> b.getType().isSolid();
    static final double MIN_RAY = 7.0;

    public BlockPlaceChecks withPrevention(PreventionConfig policy) {
        this.prevention = policy;
        return this;
    }

    public BlockPlaceChecks withStrict(StrictConfig strict) {
        this.strict = strict;
        return this;
    }

    public BlockPlaceChecks(FlagSink sink, CheckConfig cfg) {
        this(sink, cfg, AttributeSet.NONE);
    }

    public BlockPlaceChecks(FlagSink sink, CheckConfig cfg, AttributeSet attributes) {
        this.sink = sink;
        this.cfg = cfg;
        this.blockRange = attributes.blockInteractionRange();
    }

    static double rayLength(double blockInteractionRange) {
        return Double.isNaN(blockInteractionRange)
                ? 7.0
                : Math.max(7.0, blockInteractionRange + 2.5);
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public boolean onPlace(Player p, PlayerData d, Block placed, Block against, long now) {
        CheckConfig c = this.cfg;
        boolean cancel = false;
        if (c.fastPlaceEnabled()) {
            cancel |= this.fastPlace(p, d, now, c);
        }

        if (c.scaffoldEnabled() && against != null && placed != null) {
            cancel |= this.scaffold(p, d, placed, against, c);
        }

        StrictConfig s = this.strict;
        if (!cancel
                && c.scaffoldEnabled()
                && s.scaffoldFaceEnabled()
                && against != null
                && placed != null) {
            cancel |= this.scaffoldFace(p, d, placed, against, s);
        }

        return this.prevention.actions() && cancel;
    }

    private boolean fastPlace(Player p, PlayerData d, long now, CheckConfig c) {
        d.placeTimes.addLast(now);

        while (!d.placeTimes.isEmpty() && now - d.placeTimes.peekFirst() >= 1000L) {
            d.placeTimes.pollFirst();
        }

        int retained =
                c.fastPlaceMaxPerSecond() == Integer.MAX_VALUE
                        ? Integer.MAX_VALUE
                        : Math.max(1, c.fastPlaceMaxPerSecond() + 1);

        while (d.placeTimes.size() > retained) {
            d.placeTimes.pollFirst();
        }

        int perSecond = d.placeTimes.size();
        if (perSecond > c.fastPlaceMaxPerSecond()) {
            d.fastPlaceBuffer++;
            if (d.fastPlaceBuffer >= c.fastPlaceFlagAt()) {
                this.sink.flag(
                        p,
                        d,
                        "FastPlace A",
                        "More block placements per second than possible",
                        "placements_per_second_at_least="
                                + perSecond
                                + "; max="
                                + c.fastPlaceMaxPerSecond());
                return true;
            }
        } else {
            d.fastPlaceBuffer = Math.max(0.0, d.fastPlaceBuffer - 0.25);
        }

        return false;
    }

    private boolean scaffold(Player p, PlayerData d, Block placed, Block against, CheckConfig c) {
        if (!this.solid.test(against)) {
            return false;
        } else if (placed.getX() == against.getX()
                && placed.getY() == against.getY()
                && placed.getZ() == against.getZ()) {
            return false;
        } else {
            Location eyeLoc = p.getEyeLocation();
            double range = AttributeSet.value(p, this.blockRange);
            if (!Double.isFinite(range)) {
                range = 4.5;
            }

            BoundingBox clicked = BoundingBox.of(against);
            double distance =
                    PlayerHistory.boxDistance(
                            eyeLoc.getX(),
                            eyeLoc.getY(),
                            eyeLoc.getZ(),
                            clicked.getMinX(),
                            clicked.getMinY(),
                            clicked.getMinZ(),
                            clicked.getMaxX(),
                            clicked.getMaxY(),
                            clicked.getMaxZ());
            if (distance > range + 0.75 && Punisher.safePing(p) <= 300) {
                return true;
            } else {
                Vector eye = eyeLoc.toVector();
                Vector dir = eyeLoc.getDirection();
                int ping = Punisher.safePing(p);
                double tolerance = c.scaffoldTolerance() + Math.min(0.25, (double) ping / 1000.0);
                BoundingBox box = BoundingBox.of(against).expand(tolerance);
                boolean hit =
                        box.rayTrace(eye, dir, rayLength(AttributeSet.value(p, this.blockRange)))
                                != null;
                if (!hit) {
                    d.scaffoldBuffer++;
                    if (d.scaffoldBuffer >= c.scaffoldFlagAt()) {
                        this.sink.flag(
                                p,
                                d,
                                "Scaffold A",
                                "Block placed without looking at the clicked block",
                                String.format(
                                        Locale.US,
                                        "yaw=%.1f; pitch=%.1f; block=%d/%d/%d; tolerance=%.2f;"
                                                + " buffer=%.1f",
                                        eyeLoc.getYaw(),
                                        eyeLoc.getPitch(),
                                        against.getX(),
                                        against.getY(),
                                        against.getZ(),
                                        tolerance,
                                        d.scaffoldBuffer));
                        d.scaffoldBuffer = 1.0;
                    }
                } else {
                    d.scaffoldBuffer = Math.max(0.0, d.scaffoldBuffer - 0.5);
                }

                return false;
            }
        }
    }

    private boolean scaffoldFace(
            Player p, PlayerData d, Block placed, Block against, StrictConfig s) {
        int dx = placed.getX() - against.getX();
        int dy = placed.getY() - against.getY();
        int dz = placed.getZ() - against.getZ();
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
            return false;
        } else {
            double margin;
            try {
                margin = this.faceMargin.applyAsDouble(p, new Block[] {placed, against});
            } catch (Throwable ignored) {
                return false;
            }

            if (!Double.isFinite(margin)) {
                return false;
            } else {
                double tolerance =
                        0.12
                                + Math.min(
                                        0.8,
                                        Math.max(0.0, d.lastMoveHorizontal)
                                                + Math.abs(d.lastMoveDy));
                if (margin >= -tolerance) {
                    d.scaffoldFaceBuffer = Math.max(0.0, d.scaffoldFaceBuffer - 0.25);
                    return false;
                } else {
                    d.scaffoldFaceBuffer++;
                    if (d.scaffoldFaceBuffer >= s.scaffoldFaceFlagAt()) {
                        String id =
                                this.sink.flag(
                                        p,
                                        d,
                                        "Scaffold B",
                                        "Block placed against a face the player cannot see",
                                        String.format(
                                                Locale.US,
                                                "face=%s; behind_plane=%.2f; tolerance=%.2f;"
                                                        + " buffer=%.1f",
                                                against.getFace(placed),
                                                -margin,
                                                tolerance,
                                                d.scaffoldFaceBuffer));
                        if (id != null && this.prevention.actions()) {
                            this.sink.action(id, "Placement cancelled");
                        }

                        d.scaffoldFaceBuffer = 1.0;
                    }

                    return true;
                }
            }
        }
    }

    static double faceMargin(Player p, Block[] blocks) {
        Block placed = blocks[0];
        Block against = blocks[1];
        BlockFace face = against.getFace(placed);
        if (face == null) {
            return Double.NaN;
        } else {
            BoundingBox box = against.getBoundingBox();
            if (box.getVolume() <= 1.0E-6) {
                return Double.NaN;
            } else {
                boolean full;
                try {
                    full = against.getBlockData().isFaceSturdy(face, BlockSupport.FULL);
                } catch (Throwable ignored) {
                    full = false;
                }

                Location eye = p.getEyeLocation();

                return switch (face) {
                    case UP -> eye.getY() - (full ? box.getMaxY() : box.getMinY());
                    case DOWN -> (full ? box.getMinY() : box.getMaxY()) - eye.getY();
                    case EAST -> eye.getX() - (full ? box.getMaxX() : box.getMinX());
                    case WEST -> (full ? box.getMinX() : box.getMaxX()) - eye.getX();
                    case SOUTH -> eye.getZ() - (full ? box.getMaxZ() : box.getMinZ());
                    case NORTH -> (full ? box.getMinZ() : box.getMaxZ()) - eye.getZ();
                    default -> Double.NaN;
                };
            }
        }
    }
}
