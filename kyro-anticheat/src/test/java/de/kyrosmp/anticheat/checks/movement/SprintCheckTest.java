package de.kyrosmp.anticheat.checks.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.testutil.RecordingSink;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class SprintCheckTest {
    private final RecordingSink sink = new RecordingSink();
    private final SprintCheck check =
            new SprintCheck(this.sink, StrictConfig.defaults(), PreventionConfig.defaults());
    private final PlayerData d = new PlayerData(UUID.randomUUID(), "Tester");

    @Test
    void sprintWithFoodIsAllowed() {
        assertFalse(this.check.onSprintStart(null, this.d, 20, false, 50, 10_000L));
        assertEquals(0, this.sink.checks.size());
    }

    @Test
    void sprintWithFlightAllowedIsAllowed() {
        assertFalse(this.check.onSprintStart(null, this.d, 0, true, 50, 10_000L));
    }

    @Test
    void hungrySprintIsRefusedAndFlagged() {
        SprintCheck.noteFood(this.d, 7, false, 1_000L);
        assertTrue(this.check.onSprintStart(null, this.d, 6, false, 50, 5_000L));
        assertTrue(this.check.onSprintStart(null, this.d, 6, false, 50, 6_000L));
        assertEquals(1, this.sink.count("Sprint A"));
    }

    @Test
    void startRightAfterFoodDropIsNotJudged() {
        // Server dropped food to 6 at t=1000; the client may still have seen 7.
        SprintCheck.noteFood(this.d, 7, false, 1_000L);
        assertFalse(this.check.onSprintStart(null, this.d, 6, false, 150, 1_300L));
        assertEquals(0, this.sink.checks.size());
    }
}
