/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Location
 *  org.bukkit.Server
 */
package de.kyrosmp.anticheat.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;

public final class ServerStats {
    private static final MethodHandle REGION_TPS = ServerStats.findRegionTps();
    private static volatile boolean regionTpsUnavailable = REGION_TPS == null;
    private static volatile boolean globalTpsUnavailable;

    private ServerStats() {
    }

    private static MethodHandle findRegionTps() {
        try {
            return MethodHandles.publicLookup().findVirtual(Server.class, "getRegionTPS", MethodType.methodType(double[].class, Location.class));
        }
        catch (IllegalAccessException | NoSuchMethodException ex) {
            return null;
        }
    }

    public static double tps(Location at) {
        double[] tps;
        if (!regionTpsUnavailable && at != null) {
            try {
                tps = (double[]) REGION_TPS.invoke(Bukkit.getServer(), at);
                if (tps != null && tps.length > 0) {
                    return tps[0];
                }
            }
            catch (Throwable ex) {
                regionTpsUnavailable = true;
            }
        }
        if (!globalTpsUnavailable) {
            try {
                tps = Bukkit.getTPS();
                if (tps != null && tps.length > 0) {
                    return tps[0];
                }
            }
            catch (Throwable ex) {
                globalTpsUnavailable = true;
            }
        }
        return -1.0;
    }
}

