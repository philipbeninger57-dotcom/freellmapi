package de.kyrosmp.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PhysicsHardeningTest {
    @Test
    void nonfiniteAttributesCannotCreateInfiniteMovementBudgets() {
        assertEquals(0.42, Physics.jumpVelocity(Double.POSITIVE_INFINITY, -1));
        assertEquals(0.08, Physics.gravity(Double.POSITIVE_INFINITY));
        assertEquals(4.5, Physics.noFallThreshold(-1, Double.POSITIVE_INFINITY));
        assertEquals(6.0, Physics.elytraClimbBudget(Double.POSITIVE_INFINITY));
        assertTrue(Double.isFinite(Physics.maxJumpHeight(Double.POSITIVE_INFINITY, 0.08)));
        assertTrue(Double.isFinite(Physics.maxJumpHeight(Double.MAX_VALUE, 0.0)));
    }

    @Test
    void legitimateZeroGravityAndJumpAttributesArePreserved() {
        assertEquals(0.0, Physics.gravity(0.0));
        assertEquals(0.0, Physics.jumpVelocity(0.0, -1));
        assertEquals(0.0, Physics.maxJumpHeight(0.0, 0.0));
        assertTrue(Physics.maxJumpHeight(0.42, 0.0) > Physics.maxJumpHeight(0.42, 0.08));
    }

    @Test
    void potionArithmeticDoesNotWrapIntoNegativeBudgets() {
        assertTrue(Physics.jumpVelocity(0.42, Integer.MAX_VALUE) > 0.42);
        assertTrue(Physics.noFallThreshold(Integer.MAX_VALUE, 3.0) > 4.5);
        assertEquals(0.42, Physics.jumpVelocity(Double.NaN, -1));
        assertEquals(1.252203352512, Physics.maxJumpHeight(-1), 1e-9);
    }

    @Test
    void brokenTimingInputsRecoverInsteadOfPoisoningTheTimer() {
        assertEquals(0.0, Physics.timerBalance(Double.NaN, 50.0));
        assertEquals(0.0, Physics.timerBalance(0.0, Double.NaN));
        assertEquals(0.0, Physics.timerBalance(0.0, -1.0));
        assertEquals(25.0, Physics.timerBalance(0.0, 25.0));
        assertEquals(-400.0, Physics.timerBalance(-390.0, 100.0));
    }
}
