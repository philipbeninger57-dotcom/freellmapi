package de.kyrosmp.anticheat.core;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViolationLevelsTest {
    private final UUID player = UUID.randomUUID();

    @Test
    void invalidAmountsCannotPoisonOrEraseExistingEvidence() {
        ViolationLevels levels = new ViolationLevels(1.0);
        levels.add(player, "FlyA", 4.0, 0);
        assertEquals(3.0, levels.add(player, "FlyA", Double.NaN, 60_000));
        assertEquals(3.0, levels.add(player, "FlyA", Double.POSITIVE_INFINITY, 60_000));
        assertEquals(3.0, levels.add(player, "FlyA", -2.0, 60_000));
        assertEquals(2.0, levels.get(player, "FlyA", 120_000));
    }

    @Test
    void invalidDecayUsesBuiltInDefaultAndEvidenceStillExpires() {
        ViolationLevels levels = new ViolationLevels(Double.NaN);
        levels.add(player, "FlyA", 2.0, 0);
        assertEquals(1.0, levels.get(player, "FlyA", 60_000));
        levels.setDecayPerMinute(Double.POSITIVE_INFINITY);
        assertEquals(1.0, levels.get(player, "FlyA", 60_000));
        levels.cleanup(120_000);
        assertEquals(0, levels.trackedPlayers());
    }

    @Test
    void finiteAmountsCannotOverflowStoredEvidence() {
        ViolationLevels levels = new ViolationLevels(0.0);
        levels.add(player, "FlyA", Double.MAX_VALUE, 0);
        assertEquals(Double.MAX_VALUE, levels.add(player, "FlyA", Double.MAX_VALUE, 0));
        assertTrue(Double.isFinite(levels.get(player, "FlyA", 0)));
    }

    @Test
    void normalDecayAndOrderingArePreserved() {
        ViolationLevels levels = new ViolationLevels(2.0);
        levels.add(player, "FlyA", 3.0, 0);
        levels.add(player, "SpeedA", 5.0, 0);
        assertEquals("SpeedA", levels.snapshot(player, 30_000).keySet().iterator().next());
        assertEquals(2.0, levels.get(player, "FlyA", 30_000));
        levels.cleanup(150_000);
        assertEquals(0, levels.trackedPlayers());
    }
}
