package de.kyrosmp.anticheat.checks.action;

import static org.junit.jupiter.api.Assertions.*;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class FastPlaceWindowTest {
    private int flags;
    private final FlagSink sink =
            new FlagSink() {
                @Override
                public String flag(Player p, PlayerData d, String c, String r, String details) {
                    flags++;
                    return null;
                }

                @Override
                public void action(String id, String action) {}
            };

    @Test
    void largePlacementBurstUsesBoundedHistoryAndStillFlags() {
        CheckConfig cfg = CheckConfig.defaults();
        BlockPlaceChecks check = new BlockPlaceChecks(sink, cfg);
        PlayerData d = new PlayerData(UUID.randomUUID(), "test");
        for (int i = 0; i < 10_000; i++) check.onPlace(null, d, null, null, 10_000);
        assertEquals(cfg.fastPlaceMaxPerSecond() + 1, d.placeTimes.size());
        assertTrue(flags > 0);
    }

    @Test
    void discardedOldTimestampsDoNotChangeRollingThreshold() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("checks.fastplace.max-per-second", 3);
        config.set("checks.fastplace.buffer.flag-at", 1.0);
        BlockPlaceChecks check = new BlockPlaceChecks(sink, CheckConfig.from(config));
        PlayerData d = new PlayerData(UUID.randomUUID(), "test");
        for (int i = 0; i < 10; i++) check.onPlace(null, d, null, null, 10_000 + i * 100);
        int before = flags;
        // At 11,301 ms, only 10,400 through 10,900 + this placement survive.
        check.onPlace(null, d, null, null, 11_301);
        assertEquals(before + 1, flags);
        // Once every old entry has expired, a single placement is below the limit.
        check.onPlace(null, d, null, null, 12_302);
        assertEquals(before + 1, flags);
        assertEquals(1, d.placeTimes.size());
    }
}
