package de.kyrosmp.anticheat.checks.movement;

import static org.junit.jupiter.api.Assertions.*;

import de.kyrosmp.anticheat.core.*;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class ElytraCheckRegressionTest {
    private final MoveGeometry air =
            new MoveGeometry() {
                public boolean ground() {
                    return false;
                }

                public boolean restingAtPreviousXZ() {
                    return false;
                }

                public boolean liquidOrClimbable() {
                    return false;
                }

                public boolean bounceSurface() {
                    return false;
                }

                public boolean slipperySurface() {
                    return false;
                }

                public boolean ceiling() {
                    return false;
                }

                public boolean nearGround() {
                    return false;
                }

                public boolean safeNoDamageLanding() {
                    return false;
                }

                public boolean liquidUnderFeet() {
                    return false;
                }

                public boolean bodyWet() {
                    return false;
                }

                public boolean collisionNearby() {
                    return false;
                }
            };
    private int flags;
    private final FlagSink sink =
            new FlagSink() {
                public String flag(
                        Player p, PlayerData d, String check, String reason, String details) {
                    flags++;
                    return null;
                }

                public void action(String id, String action) {}
            };

    @Test
    void fireworkBoostInvalidatesTheUnconfirmedGlideAnchor() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Tester");
        data.elytraCandAt = 10000;
        data.lastElytraBoost = 20000;
        ElytraCheck check = new ElytraCheck(sink, null, CheckConfig.defaults());
        check.check(
                null,
                data,
                MoveContext.builder()
                        .time(20050, 0)
                        .from(0, 64, 0)
                        .to(0.5, 64, 0)
                        .gliding(true)
                        .geometry(air)
                        .build());
        assertEquals(0, data.elytraCandAt);
        assertEquals(0, flags);
    }

    @Test
    void creativeFlightDoesNotGenerateElytraHoverFlags() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Tester");
        ElytraCheck check = new ElytraCheck(sink, null, CheckConfig.defaults());
        for (int i = 0; i < 40; i++) {
            check.check(
                    null,
                    data,
                    MoveContext.builder()
                            .time(20000 + i * 50, 0)
                            .from(0, 64, 0)
                            .to(0, 64, 0)
                            .gameMode(GameMode.CREATIVE)
                            .gliding(true)
                            .geometry(air)
                            .build());
        }
        assertEquals(0, flags);
    }
}
