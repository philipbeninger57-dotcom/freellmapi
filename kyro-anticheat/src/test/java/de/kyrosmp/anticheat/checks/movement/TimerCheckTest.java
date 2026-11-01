package de.kyrosmp.anticheat.checks.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.testutil.Moves;
import de.kyrosmp.anticheat.testutil.RecordingSink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class TimerCheckTest {
    private RecordingSink sink;
    private MovementChecks checks;
    private PlayerData d;
    private long now;
    private double x;

    @BeforeEach
    void setUp() {
        this.sink = new RecordingSink();
        this.checks = new MovementChecks(this.sink, null, CheckConfig.defaults());
        this.d = new PlayerData(UUID.randomUUID(), "Tester");
        this.now = 1_000_000L;
        this.x = 0.0;
    }

    private void move(long afterMs) {
        this.now += afterMs;
        this.x += 0.2;
        this.checks.checkTimer(null, this.d, Moves.groundMove(this.x, 0.2, this.now).build());
    }

    private void beat() {
        this.d.heartbeat(this.now * 1_000_000L);
    }

    @Test
    void vanillaRateNeverFlags() {
        for (int i = 0; i < 400; i++) {
            move(50);
        }
        assertEquals(0, this.sink.count("Timer A"));
    }

    @Test
    void fasterGameClockFlags() {
        for (int i = 0; i < 200; i++) {
            move(38); // ~1.3x game speed
        }
        assertTrue(this.sink.count("Timer A") > 0);
    }

    /** 3.7 granted 1.5 s without limits after every 300 ms gap; pausing on purpose bypassed it. */
    @Test
    void deliberatePausesNoLongerOpenAFreeWindow() {
        move(50);
        for (int cycle = 0; cycle < 5; cycle++) {
            move(320); // short pause to trigger the old grace
            for (int i = 0; i < 40; i++) {
                move(25); // 2x game speed inside the former grace window
            }
        }
        assertTrue(this.sink.count("Timer A") > 0);
    }

    @Test
    void networkLagBurstReplaysOnlyLostTime() {
        for (int i = 0; i < 20; i++) {
            move(50);
        }
        move(1000); // packets held back by the network...
        for (int i = 0; i < 19; i++) {
            move(0); // ...and delivered at once
        }
        for (int i = 0; i < 40; i++) {
            move(50);
        }
        assertEquals(0, this.sink.count("Timer A"));
    }

    @Test
    void serverStallIsNotBlamedOnThePlayer() {
        for (int i = 0; i < 20; i++) {
            move(50);
            beat();
        }
        move(3000); // region froze for 3 s, heartbeat did not run either
        for (int i = 0; i < 59; i++) {
            move(0);
        }
        assertEquals(0, this.sink.count("Timer A"));
    }

    @Test
    void longClientPauseGrantsOnlyBoundedCredit() {
        for (int i = 0; i < 20; i++) {
            move(50);
            beat();
        }
        // The region kept ticking (heartbeat fresh) while the client went silent for 3 s.
        for (int t = 0; t < 12; t++) {
            this.now += 250;
            beat();
        }
        move(0);
        for (int i = 0; i < 59; i++) {
            move(0); // replaying 3 s at once is more than the 1 s lag credit allows
        }
        assertTrue(this.sink.count("Timer A") > 0);
    }
}
