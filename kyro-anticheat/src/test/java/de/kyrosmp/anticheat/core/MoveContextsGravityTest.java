package de.kyrosmp.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

class MoveContextsGravityTest {
    private static Player player(boolean gravityEnabled) {
        return (Player)
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (p, method, args) -> {
                            if (method.getName().equals("hasGravity")) return gravityEnabled;
                            throw new AssertionError(
                                    "unexpected player access: " + method.getName());
                        });
    }

    @Test
    void serverDisabledGravityOverridesTheAttribute() {
        assertEquals(0.0, MoveContexts.effectiveGravity(player(false), 0.08));
        assertEquals(0.0, MoveContexts.effectiveGravity(player(false), Double.NaN));
        assertEquals(0.0, Physics.gravity(MoveContexts.effectiveGravity(player(false), 0.08)));
    }

    @Test
    void normalGravityKeepsTheActualAttributeAndUnavailableFallback() {
        assertEquals(0.04, MoveContexts.effectiveGravity(player(true), 0.04));
        assertEquals(0.0, MoveContexts.effectiveGravity(player(true), 0.0));
        assertTrue(Double.isNaN(MoveContexts.effectiveGravity(player(true), Double.NaN)));
    }
}
