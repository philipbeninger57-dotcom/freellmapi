/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Location
 *  org.bukkit.World
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.PlayerData;
import org.bukkit.Location;
import org.bukkit.World;

public final class TeleportSync {
    public static final double SAME_POSITION_SQ = 0.01;
    public static final double CORRECTION_SQ = 1.0;

    private TeleportSync() {
    }

    public static Kind classifyFrom(PlayerData d, World world, double x, double y, double z, long now) {
        double distSq;
        if (world == null || !finitePosition(x, y, z)) {
            return Kind.CORRECTION;
        }
        if (!d.syncValid) {
            return Kind.CONTINUOUS;
        }
        boolean sameWorld = world == d.syncWorld;
        double d2 = distSq = sameWorld ? TeleportSync.distSq(x, y, z, d.syncX, d.syncY, d.syncZ) : Double.POSITIVE_INFINITY;
        if (distSq <= 0.01) {
            return Kind.CONTINUOUS;
        }
        return TeleportSync.classifyDestination(d, world, x, y, z, distSq, now);
    }

    public static Kind classifyTeleport(PlayerData d, Location to, long now) {
        if (to == null || to.getWorld() == null || !finitePosition(to.getX(), to.getY(), to.getZ())) {
            return Kind.CORRECTION;
        }
        World world = to.getWorld();
        double distSq = d.syncValid && world == d.syncWorld ? TeleportSync.distSq(to.getX(), to.getY(), to.getZ(), d.syncX, d.syncY, d.syncZ) : Double.POSITIVE_INFINITY;
        return TeleportSync.classifyDestination(d, world, to.getX(), to.getY(), to.getZ(), distSq, now);
    }

    private static Kind classifyDestination(PlayerData d, World world, double x, double y, double z, double distSqToSync, long now) {
        Location pending = d.pendingSetback;
        if (pending != null && d.hasFreshPendingSetback(now) && pending.getWorld() == world && TeleportSync.distSq(x, y, z, pending.getX(), pending.getY(), pending.getZ()) <= 0.01) {
            return Kind.OWN_SETBACK;
        }
        if (distSqToSync <= 1.0) {
            return Kind.CORRECTION;
        }
        return Kind.RELOCATION;
    }

    public static boolean redirectedByPlugin(PlayerData d, Object event, double toX, double toY, double toZ) {
        if (d.lowestMoveEvent != event || !finitePosition(toX, toY, toZ)) {
            return false;
        }
        return TeleportSync.distSq(toX, toY, toZ, d.lowestToX, d.lowestToY, d.lowestToZ) > 1.0E-8;
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

