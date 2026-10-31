package de.kyrosmp.anticheat.checks.action;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryMoveCheckTest {
    private int flags;
    private final FlagSink sink = new FlagSink() {
        public String flag(Player p, PlayerData d, String id, String reason, String details) {
            flags++;
            return null;
        }
        public void action(String id, String action) { }
    };
    /** The 3.7.1-custom scenarios below were written for a 250 ms opening grace. */
    private final InventoryMoveCheck check = new InventoryMoveCheck(sink, CheckConfig.defaults()).withStrict(grace(250));
    /** 3.7.2 default: 25 ms. */
    private final InventoryMoveCheck defaults = new InventoryMoveCheck(sink, CheckConfig.defaults()).withStrict(StrictConfig.defaults());

    private static StrictConfig grace(long ms) {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("checks.inventorymove.open-grace-ms", ms);
        return StrictConfig.from(yaml);
    }
    private final PlayerData data = new PlayerData(UUID.randomUUID(), "test");
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (p, method, args) -> {
                if (method.getName().equals("getGameMode")) return GameMode.SURVIVAL;
                if (method.getReturnType() == boolean.class) return false;
                return null;
            });

    private void walkingAt(long now) {
        data.lastMove = now;
        data.lastGround = true;
        data.prevMoveHorizontal = data.lastMoveHorizontal = 0.28;
    }

    @Test void rapidClicksAfterOpeningDoNotTurnEarlierWalkingIntoEvidence() {
        walkingAt(10_000);
        check.onOpen(data, 10_020);
        for (int i = 0; i < 6; i++) {
            assertFalse(check.onClick(player, data, 10_030 + i * 15L));
        }
        assertEquals(0.0, data.inventoryMoveBuffer);
        assertEquals(0, flags);
        // Finishing the opening grace does not make the pre-opening sample valid evidence.
        assertFalse(check.onClick(player, data, 10_320));
        assertEquals(0.0, data.inventoryMoveBuffer);
    }

    @Test void clientOnlyInventoryStartsGraceAtFirstObservableClick() {
        walkingAt(20_000);
        assertFalse(check.onClick(player, data, 20_010));
        assertTrue(data.inventorySessionActive);
        assertEquals(20_010, data.inventoryOpenedAt);
        walkingAt(20_060);
        assertFalse(check.onClick(player, data, 20_070));
        assertEquals(0.0, data.inventoryMoveBuffer);
        walkingAt(20_300);
        assertFalse(check.onClick(player, data, 20_310));
        walkingAt(20_350);
        assertTrue(check.onClick(player, data, 20_360));
    }

    @Test void severalClicksAgainstOneMoveCannotFillTheBuffer() {
        check.onOpen(data, 30_000);
        walkingAt(30_300);
        for (int i = 0; i < 8; i++) {
            assertFalse(check.onClick(player, data, 30_300 + i * 10L));
        }
        assertEquals(1.0, data.inventoryMoveBuffer);
        assertEquals(0, flags);
        walkingAt(30_400);
        assertTrue(check.onClick(player, data, 30_410));
    }

    @Test void continuedGroundMovementAfterGraceStillFlags() {
        check.onOpen(data, 40_000);
        for (int i = 0; i < 4; i++) {
            walkingAt(40_300 + i * 50L);
            boolean cancelled = check.onClick(player, data, data.lastMove + 5L);
            assertEquals(i > 0, cancelled);
        }
        assertEquals(1, flags);
    }

    @Test void flagDoesNotLetFurtherClicksOnTheSameMovementSampleThrough() {
        check.onOpen(data, 45_000);
        for (int i = 0; i < 4; i++) {
            walkingAt(45_300 + i * 50L);
            check.onClick(player, data, data.lastMove + 5L);
        }
        assertEquals(1, flags);
        for (int i = 1; i <= 5; i++) {
            assertTrue(check.onClick(player, data, data.lastMove + 5L + i * 10L));
        }
        assertEquals(1, flags, "Repeated clicks are prevented without duplicating evidence");
    }

    @Test void closingAndReopeningClearsPreviousSuspicion() {
        check.onOpen(data, 50_000);
        walkingAt(50_300);
        check.onClick(player, data, 50_310);
        walkingAt(50_350);
        assertTrue(check.onClick(player, data, 50_360));
        check.onClose(data);
        assertFalse(data.inventorySessionActive);
        assertEquals(0.0, data.inventoryMoveBuffer);
        check.onOpen(data, 50_370);
        walkingAt(50_380);
        assertFalse(check.onClick(player, data, 50_390));
    }

    @Test void decayingMomentumAfterGraceIsNotInventoryMovementEvidence() {
        check.onOpen(data, 60_000);
        walkingAt(60_300);
        data.lastMoveHorizontal = 0.18;
        assertFalse(check.onClick(player, data, 60_310));
        assertEquals(0.0, data.inventoryMoveBuffer);
        assertEquals(0, flags);
    }

    // ------------------------------------------------------------------ 3.7.2 default grace (25 ms)

    @Test void defaultGraceIsTwentyFiveMillisecondsAndConfigurable() {
        assertEquals(25L, defaults.openGraceMs());
        assertEquals(250L, check.openGraceMs());
        assertEquals(0L, new InventoryMoveCheck(sink, CheckConfig.defaults()).withStrict(grace(0)).openGraceMs());
    }

    @Test void fastInventoryManagerWhileSprintingIsStoppedFromTheThirdClick() {
        // Client-only player inventory: no open event, four clicks within 150 ms while sprinting.
        int cancelled = 0;
        for (int i = 0; i < 4; i++) {
            walkingAt(70_000 + i * 50L);
            boolean refused = defaults.onClick(player, data, data.lastMove + 10L);
            assertEquals(i >= 2, refused, "click " + i);
            if (refused) cancelled++;
        }
        assertEquals(2, cancelled);
        // Keeping on walking with the inventory open stays blocked.
        for (int i = 4; i < 14; i++) {
            walkingAt(70_000 + i * 50L);
            assertTrue(defaults.onClick(player, data, data.lastMove + 10L));
        }
    }

    @Test void legitDoubleClickRightAfterOpeningIsNeverRefused() {
        // Screen opened mid-sprint: both clicks see the same, last pre-opening movement sample.
        walkingAt(80_000);
        assertFalse(defaults.onClick(player, data, 80_040));
        assertFalse(defaults.onClick(player, data, 80_060));
        assertFalse(defaults.onClick(player, data, 80_090));
        assertEquals(0, flags);
    }

    @Test void containerOpenedWhileWalkingDoesNotCountTheSamplesBeforeTheScreenOpened() {
        walkingAt(90_000);
        defaults.onOpen(data, 90_010);
        assertFalse(defaults.onClick(player, data, 90_070));      // last sample predates the opening
        data.lastMoveHorizontal = 0.15;                          // keys released: momentum decays
        data.prevMoveHorizontal = 0.28;
        data.lastMove = 90_060;
        assertFalse(defaults.onClick(player, data, 90_100));
        assertEquals(0.0, data.inventoryMoveBuffer);
    }
}
