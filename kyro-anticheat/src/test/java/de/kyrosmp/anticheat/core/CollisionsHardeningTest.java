package de.kyrosmp.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

class CollisionsHardeningTest {
    private static World unloadedWorld(AtomicInteger reads) {
        return (World)
                Proxy.newProxyInstance(
                        World.class.getClassLoader(),
                        new Class<?>[] {World.class},
                        (p, m, a) -> {
                            if (m.getName().equals("isChunkLoaded")) return false;
                            if (m.getName().equals("getBlockAt")
                                    || m.getName().equals("hasCollisionsIn")) {
                                reads.incrementAndGet();
                                throw new AssertionError(
                                        "must not read or load unavailable geometry");
                            }
                            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                            if (m.getName().equals("equals")) return p == a[0];
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == int.class) return 0;
                            if (m.getReturnType() == double.class) return 0.0;
                            if (m.getReturnType() == long.class) return 0L;
                            return null;
                        });
    }

    @Test
    void massiveQueriesAreBoundedBeforeAnyWorldRead() {
        AtomicInteger reads = new AtomicInteger();
        World world = unloadedWorld(reads);
        assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> {
                    assertTrue(
                            Collisions.touches(
                                    world,
                                    new BoundingBox(-1e9, 0, -1e9, 1e9, 256, 1e9),
                                    b -> false));
                    assertTrue(
                            Collisions.touches(
                                    world,
                                    new BoundingBox(
                                            Integer.MAX_VALUE - 1.0,
                                            64,
                                            0,
                                            Integer.MAX_VALUE,
                                            65,
                                            1),
                                    b -> false));
                    assertFalse(
                            Collisions.queryable(world, new BoundingBox(-10, 0, -10, 10, 100, 10)));
                });
        assertEquals(0, reads.get());
    }

    @Test
    void unloadedGeometryCannotBeUsedAsProofOfGround() {
        AtomicInteger reads = new AtomicInteger();
        World world = unloadedWorld(reads);
        BoundingBox player = new BoundingBox(0.2, 64, 0.2, 0.8, 65.8, 0.8);
        assertFalse(Collisions.isGrounded(world, player));
        assertTrue(
                Collisions.nearGround(world, player),
                "unknown proximity remains conservative for punishment");
        assertTrue(Collisions.touchesLiquidOrClimbable(world, player));
        assertEquals(0, reads.get());
    }
}
