package de.kyrosmp.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

class PlayerStateHardeningTest {
    private final World world =
            (World)
                    Proxy.newProxyInstance(
                            World.class.getClassLoader(),
                            new Class<?>[] {World.class},
                            (p, m, a) -> {
                                if (m.getName().equals("hashCode"))
                                    return System.identityHashCode(p);
                                if (m.getName().equals("equals")) return p == a[0];
                                if (m.getName().equals("getName")) return "world";
                                return null;
                            });

    private PlayerData state() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        data.applyTeleport(new Location(world, 1, 64, 2), 10_000, false);
        return data;
    }

    @Test
    void malformedTeleportsCannotMintGraceOrPoisonState() {
        PlayerData data = state();
        for (Location invalid :
                new Location[] {
                    null,
                    new Location(null, 0, 0, 0),
                    new Location(world, Double.NaN, 100, 0),
                    new Location(world, 0, 0, 0, Float.NaN, 0)
                }) {
            data.applyTeleport(invalid, 20_000, false);
            data.applyRelocation(invalid, 20_000);
            data.applyDismount(invalid, 20_000);
        }
        assertEquals(1, data.teleportsApplied);
        assertEquals(10_000, data.lastTeleport);
        assertEquals(64, data.groundY);
        assertNull(data.lastSafe, "A teleport does not prove a safe ground anchor");
        assertTrue(data.syncValid);
    }

    @Test
    void invalidSynchronizationCannotBeClassifiedAsValid() {
        PlayerData data = state();
        data.setSync(new Location(world, Double.POSITIVE_INFINITY, 0, 0), 20_000);
        assertFalse(data.syncValid);
        assertEquals(10_000, data.syncAt);
        assertEquals(1, data.syncX);
        data.setSync(new Location(null, 0, 0, 0), 20_000);
        assertFalse(data.syncValid);
    }

    @Test
    void futureAndStaleSetbacksDoNotEarnTeleportClassification() {
        PlayerData data = state();
        data.pendingSetback = new Location(world, 2, 64, 2);
        data.pendingSetbackAt = 10_000;
        assertFalse(data.hasFreshPendingSetback(9_999));
        assertTrue(data.hasFreshPendingSetback(10_000));
        assertTrue(data.hasFreshPendingSetback(15_000));
        assertFalse(data.hasFreshPendingSetback(15_001));
    }

    @Test
    void ownSetbackCannotMintFreshTeleportGraceOrEraseTimerDebt() {
        PlayerData d = state();
        d.timerBalance = 700;
        d.applyTeleport(new Location(world, 1, 64, 2), 20_000, true);
        assertTrue(20_000 - d.lastTeleport > 30_000);
        assertEquals(700, d.timerBalance);
        assertTrue(d.safePositions.isEmpty());
        assertNotNull(d.lastSafe);
    }
}
