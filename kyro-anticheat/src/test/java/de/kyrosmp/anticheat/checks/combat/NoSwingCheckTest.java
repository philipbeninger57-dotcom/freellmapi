package de.kyrosmp.anticheat.checks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.testutil.Moves;
import de.kyrosmp.anticheat.testutil.RecordingSink;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class NoSwingCheckTest {
    private final RecordingSink sink = new RecordingSink();
    private final NoSwingCheck check =
            new NoSwingCheck(
                    null,
                    this.sink,
                    CheckConfig.defaults(),
                    StrictConfig.defaults(),
                    PreventionConfig.defaults());
    private final PlayerData d = new PlayerData(UUID.randomUUID(), "Tester");
    private final Player p = Moves.player();

    @Test
    void attacksFollowedBySwingNeverFlag() {
        for (int i = 0; i < 50; i++) {
            long seq = this.d.swingSeq;
            NoSwingCheck.onSwing(this.d);
            this.check.evaluate(this.p, this.d, seq);
        }
        assertEquals(0, this.sink.checks.size());
    }

    @Test
    void attacksWithoutSwingFlag() {
        for (int i = 0; i < 3; i++) {
            this.check.evaluate(this.p, this.d, this.d.swingSeq);
        }
        assertEquals(1, this.sink.count("KillAura D"));
    }

    @Test
    void occasionalMissingSwingDecays() {
        for (int i = 0; i < 40; i++) {
            long seq = this.d.swingSeq;
            if (i % 10 != 0) {
                NoSwingCheck.onSwing(this.d);
            }
            this.check.evaluate(this.p, this.d, seq);
        }
        assertEquals(0, this.sink.checks.size());
        assertTrue(this.d.noSwingBuffer < 3.0);
    }
}
