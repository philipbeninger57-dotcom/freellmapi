package de.kyrosmp.anticheat.core;

import org.bukkit.Location;
import org.bukkit.World;

public final class TeleportSync {
    public static final double SAME_POSITION_SQ = 0.01;
    public static final double CORRECTION_SQ = 1.0;

    private TeleportSync() {}

    public static TeleportSync.Kind classifyFrom(
            PlayerData d, World world, double x, double y, double z, long now) {
        if (world == null || !finitePosition(x, y, z)) {
            return TeleportSync.Kind.CORRECTION;
        } else if (!d.syncValid) {
            return TeleportSync.Kind.CONTINUOUS;
        } else {
            boolean sameWorld = world == d.syncWorld;
            double distSq =
                    sameWorld
                            ? distSq(x, y, z, d.syncX, d.syncY, d.syncZ)
                            : Double.POSITIVE_INFINITY;
            return distSq <= 0.01
                    ? TeleportSync.Kind.CONTINUOUS
                    : classifyDestination(d, world, x, y, z, distSq, now);
        }
    }

    public static TeleportSync.Kind classifyTeleport(PlayerData d, Location to, long now) {
        if (to != null
                && to.getWorld() != null
                && finitePosition(to.getX(), to.getY(), to.getZ())) {
            World world = to.getWorld();
            double distSq =
                    d.syncValid && world == d.syncWorld
                            ? distSq(to.getX(), to.getY(), to.getZ(), d.syncX, d.syncY, d.syncZ)
                            : Double.POSITIVE_INFINITY;
            return classifyDestination(d, world, to.getX(), to.getY(), to.getZ(), distSq, now);
        } else {
            return TeleportSync.Kind.CORRECTION;
        }
    }

    private static TeleportSync.Kind classifyDestination(
            PlayerData d,
            World world,
            double x,
            double y,
            double z,
            double distSqToSync,
            long now) {
        Location pending = d.pendingSetback;
        if (pending != null
                && d.hasFreshPendingSetback(now)
                && pending.getWorld() == world
                && distSq(x, y, z, pending.getX(), pending.getY(), pending.getZ()) <= 0.01) {
            return TeleportSync.Kind.OWN_SETBACK;
        } else {
            return distSqToSync <= 1.0
                    ? TeleportSync.Kind.CORRECTION
                    : TeleportSync.Kind.RELOCATION;
        }
    }

    public static boolean redirectedByPlugin(
            PlayerData d, Object event, double toX, double toY, double toZ) {
        return d.lowestMoveEvent == event && finitePosition(toX, toY, toZ)
                ? distSq(toX, toY, toZ, d.lowestToX, d.lowestToY, d.lowestToZ) > 1.0E-8
                : false;
    }

    private static boolean finitePosition(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    public static double distSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    public static enum Kind {
        CONTINUOUS,
        CORRECTION,
        OWN_SETBACK,
        RELOCATION;
    }
}
