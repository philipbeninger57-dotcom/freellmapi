package de.kyrosmp.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

class TeleportSyncHardeningTest {
    private final World world =
            (World)
                    Proxy.newProxyInstance(
                            World.class.getClassLoader(),
                            new Class<?>[] {World.class},
                            (p, m, a) -> {
                                if (m.getName().equals("hashCode"))
                                    return System.identityHashCode(p);
                                if (m.getName().equals("equals")) return p == a[0];
                                return null;
                            });

    @Test
    void invalidCoordinatesDoNotMintRelocationGrace() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "tester");
        data.setSync(new Location(world, 0, 64, 0), 1000);
        assertEquals(
                TeleportSync.Kind.CORRECTION,
                TeleportSync.classifyFrom(data, world, Double.NaN, 64, 0, 1050));
        assertEquals(
                TeleportSync.Kind.CORRECTION,
                TeleportSync.classifyTeleport(
                        data, new Location(world, 0, Double.POSITIVE_INFINITY, 0), 1050));
        assertEquals(
                TeleportSync.Kind.CORRECTION,
                TeleportSync.classifyTeleport(data, new Location(null, 0, 64, 0), 1050));
        assertEquals(
                TeleportSync.Kind.RELOCATION,
                TeleportSync.classifyTeleport(data, new Location(world, 1000, 64, 0), 1050));
    }

    @Test
    void validOwnSetbacksAndOrdinaryMovementKeepTheirClassification() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "tester");
        data.setSync(new Location(world, 0, 64, 0), 1000);
        data.pendingSetback = new Location(world, 10, 64, 0);
        data.pendingSetbackAt = 1000;
        assertEquals(
                TeleportSync.Kind.CONTINUOUS,
                TeleportSync.classifyFrom(data, world, 0.05, 64, 0, 1050));
        assertEquals(
                TeleportSync.Kind.OWN_SETBACK,
                TeleportSync.classifyTeleport(data, data.pendingSetback, 1050));
        assertEquals(
                TeleportSync.Kind.CORRECTION,
                TeleportSync.classifyTeleport(data, new Location(world, 0.5, 64, 0), 1050));
    }
}
