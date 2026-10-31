package de.kyrosmp.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlayerHistoryTest {
    private static PlayerHistory.MoveSample move(long time, double x) {
        return new PlayerHistory.MoveSample(time, x, 0, 0, 0, 0, 0, 0, true, 0);
    }

    @Test
    void oldPositionCannotExemptCurrentReach() {
        PlayerHistory history = new PlayerHistory(5, 1);
        history.addMove(move(100, 0));
        history.addMove(move(1000, 10));
        assertEquals(9.7, history.minHitboxDistance(800, 0, 1, 0, .3, 1.8, 0), 1e-9);
    }

    @Test
    void onlyStaleHistoryHasNoLagCompensation() {
        PlayerHistory history = new PlayerHistory(5, 1);
        history.addMove(move(100, 0));
        assertEquals(Double.POSITIVE_INFINITY, history.minHitboxDistance(800, 0, 1, 0, .3, 1.8, 0));
    }

    @Test
    void sampleExactlyAtCutoffIsIncluded() {
        PlayerHistory history = new PlayerHistory(5, 1);
        history.addMove(move(800, 1));
        assertEquals(.7, history.minHitboxDistance(800, 0, 1, 0, .3, 1.8, 0), 1e-9);
    }
}
