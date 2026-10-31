package de.kyrosmp.anticheat.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class ServerStats {
    private static final MethodHandle REGION_TPS = findRegionTps();
    private static volatile boolean regionTpsUnavailable = REGION_TPS == null;
    private static volatile boolean globalTpsUnavailable;

    private ServerStats() {}

    private static MethodHandle findRegionTps() {
        try {
            return MethodHandles.publicLookup()
                    .findVirtual(
                            Server.class,
                            "getRegionTPS",
                            MethodType.methodType(double[].class, Location.class));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            return null;
        }
    }

    public static double tps(Location at) {
        if (!regionTpsUnavailable && at != null) {
            try {
                double[] tps =
                        (double[]) REGION_TPS.invoke((Server) Bukkit.getServer(), (Location) at);
                if (tps != null && tps.length > 0) {
                    return tps[0];
                }
            } catch (Throwable ignored) {
                regionTpsUnavailable = true;
            }
        }

        if (!globalTpsUnavailable) {
            try {
                double[] tps = Bukkit.getTPS();
                if (tps != null && tps.length > 0) {
                    return tps[0];
                }
            } catch (Throwable ignored) {
                globalTpsUnavailable = true;
            }
        }

        return -1.0;
    }
}
