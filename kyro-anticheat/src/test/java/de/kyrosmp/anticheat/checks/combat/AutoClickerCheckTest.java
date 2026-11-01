package de.kyrosmp.anticheat.checks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class AutoClickerCheckTest {
    private static final class RecordingSink implements FlagSink {
        int flags;

        @Override
        public String flag(Player p, PlayerData d, String check, String summary, String details) {
            flags++;
            return "test";
        }

        @Override
        public void action(String id, String action) {}
    }

    @Test
    void delayedWindowsUseActualElapsedTime() {
        RecordingSink sink = new RecordingSink();
        AutoClickerCheck check = new AutoClickerCheck(sink, CheckConfig.defaults());
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        for (int i = 0; i < 3; i++) {
            // 40 swings over 1.5 seconds = 26.67 CPS, below the 30 CPS limit.
            // The original implementation compared 40 directly against 30.
            data.swingSecondStart = 10_000 + i * 1500;
            data.swingCount = 40;
            check.onSwing(null, data, data.swingSecondStart + 1500);
        }
        assertEquals(0, sink.flags);
        assertEquals(0, data.highCpsSeconds);
    }

    @Test
    void sustainedExcessiveRateStillFlags() {
        RecordingSink sink = new RecordingSink();
        AutoClickerCheck check = new AutoClickerCheck(sink, CheckConfig.defaults());
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        for (int i = 0; i < 3; i++) {
            data.swingSecondStart = 10_000 + i * 1000;
            data.swingCount = 40;
            check.onSwing(null, data, data.swingSecondStart + 1000);
        }
        assertEquals(1, sink.flags);
    }

    @Test
    void dropAllowanceCanOnlyHideOneSwing() {
        RecordingSink sink = new RecordingSink();
        AutoClickerCheck check = new AutoClickerCheck(sink, CheckConfig.defaults());
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        data.swingSecondStart = 10_000;
        data.lastDrop = 10_000;
        for (int i = 0; i < 5; i++) {
            check.onSwing(null, data, 10_010 + i);
        }
        assertEquals(4, data.swingCount);
        assertEquals(0, data.lastDrop);
    }

    @Test
    void frequentRealDropsCannotHideSustainedExcessiveSwings() {
        RecordingSink sink = new RecordingSink();
        AutoClickerCheck check = new AutoClickerCheck(sink, CheckConfig.defaults());
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        data.swingSecondStart = 10_000;
        // 100 swings/s and 20 successful drops/s: at most 20 animations/s
        // are excluded; the remaining rate still exceeds the 30 CPS threshold.
        for (long now = 10_000; now <= 13_010; now += 10) {
            if (now % 50 == 0) data.lastDrop = now;
            check.onSwing(null, data, now);
        }
        assertEquals(1, sink.flags);
    }

    @Test
    void matchingSuccessfulDropForEverySwingDoesNotHideExcessiveRate() {
        RecordingSink sink = new RecordingSink();
        AutoClickerCheck check = new AutoClickerCheck(sink, CheckConfig.defaults());
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        data.swingSecondStart = 10_000;
        for (long now = 10_000; now <= 13_010; now += 10) {
            data.lastDrop = now; // Even an actually successful drop on every swing.
            check.onSwing(null, data, now);
        }
        assertEquals(1, sink.flags);
    }

    @Test
    void twentyDropsAndTwentyOrdinaryAttackAnimationsPerSecondRemainBelowLimit() {
        RecordingSink sink = new RecordingSink();
        AutoClickerCheck check = new AutoClickerCheck(sink, CheckConfig.defaults());
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        data.swingSecondStart = 10_000;
        for (long now = 10_000; now <= 14_000; now += 50) {
            data.lastDrop = now;
            check.onSwing(null, data, now); // Conservative: assume a drop animation.
            check.onSwing(null, data, now); // Normal 20 CPS attack stream.
        }
        assertEquals(0, sink.flags);
        assertEquals(0, data.highCpsSeconds);
    }
}
