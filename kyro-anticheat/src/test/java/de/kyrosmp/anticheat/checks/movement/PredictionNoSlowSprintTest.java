package de.kyrosmp.anticheat.checks.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.MoveContext;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.testutil.Moves;
import de.kyrosmp.anticheat.testutil.RecordingSink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.UnaryOperator;

class PredictionNoSlowSprintTest {
    private RecordingSink sink;
    private PredictionChecks prediction;
    private PlayerData d;
    private long now;
    private double x;
    private int rejected;

    @BeforeEach
    void setUp() {
        this.sink = new RecordingSink();
        this.prediction =
                new PredictionChecks(
                        this.sink, null, CheckConfig.defaults(), StrictConfig.defaults());
        this.d = new PlayerData(UUID.randomUUID(), "Tester");
        this.d.resetPrediction(true);
        this.now = 1_000_000L;
        this.x = 0.0;
        this.rejected = 0;
    }

    private void groundMoves(int count, double horizontal, UnaryOperator<MoveContext.Builder> f) {
        for (int i = 0; i < count; i++) {
            this.now += 50;
            MoveContext m = f.apply(Moves.groundMove(this.x, horizontal, this.now)).build();
            this.x += horizontal;
            if (this.prediction.evaluate(null, this.d, m, true, true, null, false)) {
                this.rejected++;
            }
        }
    }

    @Test
    void sneakingAtSneakSpeedIsFine() {
        groundMoves(60, 0.085, b -> b.sneaking(true).sneakingSpeedAttr(0.3));
        assertEquals(0, this.rejected);
        assertEquals(0, this.sink.checks.size());
    }

    @Test
    void sneakingAtWalkingSpeedIsNoSlowB() {
        groundMoves(60, 0.215, b -> b.sneaking(true).sneakingSpeedAttr(0.3));
        assertTrue(this.rejected > 0);
        assertTrue(this.sink.count("NoSlow B") > 0);
    }

    @Test
    void swiftSneakRaisesTheLimit() {
        // Swift Sneak III: sneaking_speed 0.75 is not judged as slowed movement at all.
        groundMoves(60, 0.215, b -> b.sneaking(true).sneakingSpeedAttr(0.75));
        assertEquals(0, this.rejected);
    }

    @Test
    void walkingWithoutSneakIsFine() {
        groundMoves(60, 0.215, b -> b);
        assertEquals(0, this.rejected);
    }

    @Test
    void sprintSpeedWithFoodIsFine() {
        groundMoves(60, 0.28, b -> b.foodLevel(20));
        assertEquals(0, this.rejected);
    }

    @Test
    void hungerSprintWithoutServerSprintIsSpeedB() {
        groundMoves(60, 0.28, b -> b.foodLevel(4));
        assertTrue(this.rejected > 0);
        assertTrue(this.sink.count("Speed B") > 0);
    }

    @Test
    void hungryWalkingIsFine() {
        groundMoves(60, 0.215, b -> b.foodLevel(4));
        assertEquals(0, this.rejected);
    }

    @Test
    void sprintKeptFromBeforeTheFoodDropIsFine() {
        // Server-side sprinting started while food was above 6: vanilla may keep sprinting.
        groundMoves(60, 0.28, b -> b.foodLevel(4).sprinting(true).movementSpeedAttr(0.13));
        assertEquals(0, this.rejected);
    }

    @Test
    void sprintAllowanceIsHeldBrieflyAfterFoodDrops() {
        groundMoves(20, 0.28, b -> b.foodLevel(7));
        this.now += 50;
        MoveContext m = Moves.groundMove(this.x, 0.28, this.now).foodLevel(6).build();
        assertTrue(PredictionChecks.sprintPossible(this.d, m, this.now));
        this.now += 2000;
        MoveContext later = Moves.groundMove(this.x, 0.28, this.now).foodLevel(6).build();
        assertFalse(PredictionChecks.sprintPossible(this.d, later, this.now));
    }
}
