package de.kyrosmp.anticheat.checks.movement;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.SetbackService;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Position silence can be a frozen connection and must never create cheat evidence. */
class HoverSilenceRegressionTest {
    private int corrections;
    private boolean correctionSucceeds = true;
    private final FlagSink noEvidence = new FlagSink() {
        public String flag(Player p, PlayerData d, String check, String reason, String details) {
            fail("Position silence must not produce a flag");
            return null;
        }
        public void action(String id, String action) {
            fail("A silence correction must not be attached to cheat evidence");
        }
    };
    private final SetbackService setbacks = new SetbackService() {
        public boolean setback(Player p, PlayerData d, String id) {
            assertNull(id, "silence corrections have no evidence ID");
            corrections++;
            return correctionSucceeds;
        }
        public boolean setbackTo(Player p, PlayerData d, Location at, String id) {
            fail("Use validated ground selection for an unsupported position");
            return false;
        }
        public void registerFlyPunishment(Player p, PlayerData d, long now, String id) {
            fail("Silence must not trigger a punishment incident");
        }
    };
    private final PlayerData data = new PlayerData(UUID.randomUUID(), "FrozenClient");
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (p, method, args) -> {
                return switch (method.getName()) {
                    case "isOnline", "hasGravity" -> true;
                    case "getGameMode" -> GameMode.SURVIVAL;
                    default -> method.getReturnType() == boolean.class ? false : null;
                };
            });

    private HoverCheck check() {
        HoverCheck check = new HoverCheck(noEvidence, setbacks, CheckConfig.defaults(), StrictConfig.defaults());
        check.probe = p -> new HoverCheck.Probe(true, false, false, false, 80.0);
        check.floatEffect = p -> false;
        data.lastPositionChangeAt = 10_000;
        return check;
    }

    @Test void repeatedPositionSilenceCanBeCorrectedWithoutIncreasingSuspicion() {
        HoverCheck check = check();
        data.hoverBuffer = 2.0; // Existing state must not gain new evidence from silence.
        assertFalse(check.tick(player, data, 20_000));
        assertTrue(check.tick(player, data, 20_250));
        assertFalse(check.tick(player, data, 21_500));
        assertTrue(check.tick(player, data, 21_750));
        assertEquals(2, corrections);
        assertEquals(2.0, data.hoverBuffer);
        assertFalse(data.preventMove);
    }

    @Test void unavailableSafeGroundDoesNotTurnSilenceIntoAFlagOrRapidRetryLoop() {
        HoverCheck check = check();
        correctionSucceeds = false;
        assertFalse(check.tick(player, data, 20_000));
        assertFalse(check.tick(player, data, 20_250));
        assertFalse(check.tick(player, data, 20_500));
        assertFalse(check.tick(player, data, 20_750));
        assertEquals(1, corrections);
        assertEquals(0.0, data.hoverBuffer);
    }

    @Test void preventionDisabledLeavesSilenceWithoutCorrectionOrEvidence() {
        HoverCheck check = check().withPrevention(new PreventionConfig(false, true, true, true, true));
        for (int i = 0; i < 20; i++) {
            assertFalse(check.tick(player, data, 20_000 + i * 250));
        }
        assertEquals(0, corrections);
        assertEquals(0.0, data.hoverBuffer);
    }

    @Test void resumedPositionUpdatesRequireANewStillPeriodAndBothConfirmations() {
        HoverCheck check = check();
        assertFalse(check.tick(player, data, 20_000));
        data.lastPositionChangeAt = 20_100;
        assertFalse(check.tick(player, data, 20_250));
        assertFalse(check.tick(player, data, 21_400));
        assertTrue(check.tick(player, data, 21_650));
        assertEquals(1, corrections);
    }
}
