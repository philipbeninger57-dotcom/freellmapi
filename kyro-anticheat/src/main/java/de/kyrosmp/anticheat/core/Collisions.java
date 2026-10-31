/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Location
 *  org.bukkit.Material
 *  org.bukkit.World
 *  org.bukkit.block.Block
 *  org.bukkit.block.data.BlockData
 *  org.bukkit.block.data.Waterlogged
 *  org.bukkit.entity.Player
 *  org.bukkit.util.BoundingBox
 */
package de.kyrosmp.anticheat.core;

import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

public final class Collisions {
    private static final long MAX_BLOCKS_PER_QUERY = 4096L;
    public static final int LIQUID = 1;
    public static final int CLIMBABLE = 2;
    public static final int STUCK = 4;
    /** Every category: an unreadable area must never be treated as proof of cheating. */
    public static final int UNKNOWN = LIQUID | CLIMBABLE | STUCK;

    /**
     * One scan that separates liquids, climbables and slowing blocks. Unreadable areas
     * (unloaded or owned by another Folia region) report every category.
     */
    public static int classify(World w, BoundingBox box) {
        if (!queryable(w, box)) return UNKNOWN;
        try {
            int mask = 0;
            int minX = (int)Math.floor(box.getMinX());
            int maxX = (int)Math.floor(box.getMaxX());
            int minY = (int)Math.floor(box.getMinY());
            int maxY = (int)Math.floor(box.getMaxY());
            int minZ = (int)Math.floor(box.getMinZ());
            int maxZ = (int)Math.floor(box.getMaxZ());
            for (int x = minX; x <= maxX; ++x) {
                for (int y = minY; y <= maxY; ++y) {
                    for (int z = minZ; z <= maxZ; ++z) {
                        Block b = w.getBlockAt(x, y, z);
                        Material type = b.getType();
                        if (type.isAir()) continue;
                        mask |= category(type.name(), b.isLiquid() || waterlogged(b));
                        if (mask == UNKNOWN) return mask;
                    }
                }
            }
            return mask;
        }
        catch (Throwable ex) {
            return UNKNOWN;
        }
    }

    static int category(String n, boolean wet) {
        int mask = 0;
        if (wet || n.contains("WATER") || n.contains("LAVA") || n.contains("BUBBLE") || n.contains("KELP") || n.contains("SEAGRASS")) {
            mask |= LIQUID;
        }
        if (n.contains("LADDER") || n.contains("VINE") || n.contains("SCAFFOLDING")) {
            mask |= CLIMBABLE;
        }
        if (n.contains("COBWEB") || n.contains("POWDER_SNOW") || n.equals("SWEET_BERRY_BUSH") || n.equals("HONEY_BLOCK")) {
            mask |= STUCK;
        }
        return mask;
    }

    /**
     * Entities a player can stand on or be carried by: boats/rafts, minecarts, shulkers and happy
     * ghasts. Server-side block collision queries may not see all of them as support.
     */
    public static boolean platformEntityNearby(Player p) {
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(2.5, 3.0, 2.5)) {
            String n = e.getType().name();
            if (n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.equals("BOAT") || n.equals("CHEST_BOAT")
                    || n.contains("MINECART") || n.equals("SHULKER") || n.equals("HAPPY_GHAST")) {
                return true;
            }
        }
        return false;
    }

    /** Pistons in motion, piston heads and shulker boxes can lift or shove players. */
    public static boolean movingBlockNearby(World w, BoundingBox box) {
        return touches(w, box.clone().expand(1.5, 1.5, 1.5), b -> {
            String n = b.getType().name();
            return n.equals("MOVING_PISTON") || n.equals("PISTON_HEAD") || n.endsWith("SHULKER_BOX");
        });
    }

    /**
     * Finds the first supported, free player position straight below {@code at} (at most
     * {@code maxDepth} blocks). Used when no recorded safe anchor exists, for example right after
     * a teleport. Never loads chunks or crosses a Folia region; returns null when unsure.
     */
    public static Location groundBelow(Player p, Location at, int maxDepth) {
        if (at == null || at.getWorld() == null || !Double.isFinite(at.getY())) return null;
        World w = at.getWorld();
        try {
            int startY = (int)Math.floor(at.getY());
            int minY = Math.max(w.getMinHeight() + 1, startY - Math.max(1, maxDepth));
            if (!queryable(w, playerBox(p, at))) return null;
            for (int y = startY; y >= minY; --y) {
                Block below = w.getBlockAt(at.getBlockX(), y - 1, at.getBlockZ());
                if (below.isLiquid()) return null;
                if (below.isPassable()) continue;
                double top = below.getBoundingBox().getMaxY();
                if (!Double.isFinite(top) || top > at.getY() + 0.001) continue;
                // The first solid block decides: never search through it into caves below.
                Location candidate = new Location(w, at.getX(), top, at.getZ(), at.getYaw(), at.getPitch());
                BoundingBox box = playerBox(p, candidate);
                BoundingBox body = box.clone().expand(-0.001, 0.0, -0.001).shift(0.0, 0.001, 0.0);
                if (!queryable(w, box.clone().expand(0.0, 0.2, 0.0))) return null;
                if (hasCollision(w, body, true) || !isGrounded(w, box)) return null;
                return candidate;
            }
        }
        catch (Throwable ex) {
            return null;
        }
        return null;
    }

    private Collisions() {
    }

    public static BoundingBox playerBox(Player p, Location at) {
        double width = 0.6;
        double height = 1.8;
        try {
            BoundingBox current = p.getBoundingBox();
            double measuredWidth = current.getWidthX();
            double measuredHeight = current.getHeight();
            if (Double.isFinite(measuredWidth) && measuredWidth > 0.0) width = measuredWidth;
            if (Double.isFinite(measuredHeight) && measuredHeight > 0.0) height = measuredHeight;
        }
        catch (Throwable current) {
            // empty catch block
        }
        double half = width / 2.0;
        return new BoundingBox(at.getX() - half, at.getY(), at.getZ() - half, at.getX() + half, at.getY() + height, at.getZ() + half);
    }

    public static boolean isGrounded(World w, BoundingBox box) {
        BoundingBox below = new BoundingBox(box.getMinX() + 0.001, box.getMinY() - 0.12, box.getMinZ() + 0.001, box.getMaxX() - 0.001, box.getMinY(), box.getMaxZ() - 0.001);
        if (!queryable(w, below)) return false;
        try {
            return w.hasCollisionsIn(below);
        }
        catch (Throwable ex) {
            // Failed queries are not proof of support beneath a player.
            return touches(w, below, b -> !b.isPassable() && b.getBoundingBox().overlaps(below), false);
        }
    }

    /** Exact contact with a collision surface below (within 0.002 blocks), like vanilla's onGround. */
    public static boolean touchingGround(World w, BoundingBox box) {
        BoundingBox below = new BoundingBox(box.getMinX() + 0.001, box.getMinY() - 0.002, box.getMinZ() + 0.001, box.getMaxX() - 0.001, box.getMinY(), box.getMaxZ() - 0.001);
        if (!queryable(w, below)) return true;
        try {
            return w.hasCollisionsIn(below);
        }
        catch (Throwable ex) {
            return true;
        }
    }

    public static boolean restingAt(World w, Player p, double x, double y, double z) {
        BoundingBox box = Collisions.playerBox(p, new Location(w, x, y, z));
        if (!Collisions.isGrounded(w, box)) {
            return false;
        }
        BoundingBox above = new BoundingBox(box.getMinX() + 0.001, box.getMinY() + 0.01, box.getMinZ() + 0.001, box.getMaxX() - 0.001, box.getMinY() + 0.6, box.getMaxZ() - 0.001);
        try {
            return queryable(w, above) && !w.hasCollisionsIn(above);
        }
        catch (Throwable ex) {
            return false;
        }
    }

    public static boolean nearGround(World w, BoundingBox box) {
        BoundingBox area = new BoundingBox(box.getMinX() - 0.15, box.getMinY() - 0.6, box.getMinZ() - 0.15, box.getMaxX() + 0.15, box.getMinY() + 0.1, box.getMaxZ() + 0.15);
        try {
            return hasCollision(w, area, true) || Collisions.touchesLiquidOrClimbable(w, box);
        }
        catch (Throwable ex) {
            return true;
        }
    }

    public static boolean hasCeiling(World w, BoundingBox box) {
        BoundingBox above = new BoundingBox(box.getMinX() + 0.001, box.getMaxY(), box.getMinZ() + 0.001, box.getMaxX() - 0.001, box.getMaxY() + 0.6, box.getMaxZ() - 0.001);
        try {
            return hasCollision(w, above, true);
        }
        catch (Throwable ex) {
            return true;
        }
    }

    public static boolean isGroundLike(Material m) {
        String n = m.name();
        return n.contains("CARPET") || n.contains("SNOW") || n.contains("LILY_PAD") || n.contains("SCAFFOLDING") || n.contains("TRAPDOOR");
    }

    public static boolean touchesLiquidOrClimbable(World w, BoundingBox box) {
        return Collisions.touches(w, box.clone().expand(0.3, 0.3, 0.3), b -> b.isLiquid() || Collisions.isLiquidOrClimbable(b.getType().name()) || Collisions.waterlogged(b));
    }

    public static boolean isLiquidOrClimbable(String n) {
        return n.contains("WATER") || n.contains("LAVA") || n.contains("BUBBLE") || n.contains("KELP") || n.contains("SEAGRASS") || n.contains("LADDER") || n.contains("VINE") || n.contains("SCAFFOLDING") || n.contains("COBWEB") || n.contains("POWDER_SNOW") || n.equals("HONEY_BLOCK") || n.equals("SWEET_BERRY_BUSH");
    }

    public static BoundingBox feetArea(BoundingBox box) {
        return new BoundingBox(box.getMinX() - 0.05, box.getMinY() - 0.6, box.getMinZ() - 0.05, box.getMaxX() + 0.05, box.getMinY() + 0.2, box.getMaxZ() + 0.05);
    }

    public static boolean onBounceSurface(World w, BoundingBox box) {
        return Collisions.touches(w, Collisions.feetArea(box), b -> {
            String n = b.getType().name();
            return n.equals("SLIME_BLOCK") || n.endsWith("_BED") || n.equals("HONEY_BLOCK");
        });
    }

    public static boolean onSlipperySurface(World w, BoundingBox box) {
        return Collisions.touches(w, Collisions.feetArea(box), b -> {
            String n = b.getType().name();
            return n.contains("ICE") || n.equals("SLIME_BLOCK") || n.equals("SOUL_SAND") || n.equals("SOUL_SOIL");
        });
    }

    public static boolean safeNoDamageLanding(World w, BoundingBox box) {
        return Collisions.touches(w, Collisions.feetArea(box), b -> {
            if (b.isLiquid() || Collisions.waterlogged(b)) {
                return true;
            }
            String n = b.getType().name();
            return n.contains("WATER") || n.contains("BUBBLE") || n.contains("SLIME") || n.contains("HAY") || n.contains("HONEY") || n.contains("COBWEB") || n.contains("POWDER_SNOW") || n.endsWith("_BED") || n.contains("LADDER") || n.contains("VINE") || n.contains("SCAFFOLDING");
        });
    }

    public static boolean touches(World w, BoundingBox box, Predicate<Block> test) {
        return touches(w, box, test, true);
    }

    private static boolean touches(World w, BoundingBox box, Predicate<Block> test, boolean unknown) {
        if (!queryable(w, box)) return unknown;
        try {
            int minX = (int)Math.floor(box.getMinX());
            int maxX = (int)Math.floor(box.getMaxX());
            int minY = (int)Math.floor(box.getMinY());
            int maxY = (int)Math.floor(box.getMaxY());
            int minZ = (int)Math.floor(box.getMinZ());
            int maxZ = (int)Math.floor(box.getMaxZ());
            for (int x = minX; x <= maxX; ++x) {
                for (int y = minY; y <= maxY; ++y) {
                    for (int z = minZ; z <= maxZ; ++z) {
                        if (!test.test(w.getBlockAt(x, y, z))) continue;
                        return true;
                    }
                }
            }
            return false;
        }
        catch (Throwable ex) {
            return unknown;
        }
    }

    static boolean hasCollision(World world, BoundingBox box, boolean unknown) {
        if (!queryable(world, box)) return unknown;
        try {
            return world.hasCollisionsIn(box);
        } catch (Throwable ex) {
            return unknown;
        }
    }

    // World lookups must never load chunks, cross a Folia region, or perform an
    // unbounded scan. A normal player (including vanilla maximum scale) fits.
    public static boolean queryable(World world, BoundingBox box) {
        if (world == null || box == null) return false;
        double[] bounds = {box.getMinX(), box.getMaxX(), box.getMinY(), box.getMaxY(), box.getMinZ(), box.getMaxZ()};
        for (double value : bounds) {
            if (!Double.isFinite(value) || value <= Integer.MIN_VALUE || value >= Integer.MAX_VALUE) return false;
        }
        long sx = (long)Math.floor(box.getMaxX()) - (long)Math.floor(box.getMinX()) + 1L;
        long sy = (long)Math.floor(box.getMaxY()) - (long)Math.floor(box.getMinY()) + 1L;
        long sz = (long)Math.floor(box.getMaxZ()) - (long)Math.floor(box.getMinZ()) + 1L;
        if (sx < 1L || sy < 1L || sz < 1L || sx > MAX_BLOCKS_PER_QUERY || sy > MAX_BLOCKS_PER_QUERY || sz > MAX_BLOCKS_PER_QUERY
                || sx * sy > MAX_BLOCKS_PER_QUERY || sx * sy * sz > MAX_BLOCKS_PER_QUERY) return false;
        try {
            int minChunkX = ((int)Math.floor(box.getMinX())) >> 4;
            int maxChunkX = ((int)Math.floor(box.getMaxX())) >> 4;
            int minChunkZ = ((int)Math.floor(box.getMinZ())) >> 4;
            int maxChunkZ = ((int)Math.floor(box.getMaxZ())) >> 4;
            for (int x = minChunkX; x <= maxChunkX; x++) {
                for (int z = minChunkZ; z <= maxChunkZ; z++) {
                    if (!world.isChunkLoaded(x, z) || !Bukkit.isOwnedByCurrentRegion(world, x, z)) return false;
                }
            }
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    public static boolean waterlogged(Block b) {
        Waterlogged wl;
        BlockData data = b.getBlockData();
        return data instanceof Waterlogged && (wl = (Waterlogged)data).isWaterlogged();
    }
}

