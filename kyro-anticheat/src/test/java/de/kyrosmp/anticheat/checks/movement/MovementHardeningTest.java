package de.kyrosmp.anticheat.checks.movement;

import static org.junit.jupiter.api.Assertions.*;

import de.kyrosmp.anticheat.core.*;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class MovementHardeningTest {
    private final List<String> flags = new ArrayList<>();
    private int kickIncidents;
    private final FlagSink sink =
            new FlagSink() {
                public String flag(
                        Player p, PlayerData d, String check, String reason, String details) {
                    flags.add(check);
                    return "flag";
                }

                public void action(String id, String action) {}
            };
    private final SetbackService setbacks =
            new SetbackService() {
                public boolean setback(Player p, PlayerData d, String id) {
                    return false;
                }

                public boolean setbackTo(Player p, PlayerData d, Location at, String id) {
                    return false;
                }

                public void registerFlyPunishment(Player p, PlayerData d, long now, String id) {
                    kickIncidents++;
                }
            };
    private final MovementChecks checks =
            new MovementChecks(sink, setbacks, CheckConfig.defaults());

    private static MoveGeometry geometry(boolean collision) {
        return new MoveGeometry() {
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
                return collision;
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
                return collision;
            }
        };
    }

    private static PlayerData data() {
        PlayerData d = new PlayerData(UUID.randomUUID(), "Tester");
        d.groundY = 64;
        return d;
    }

    private static MoveContext position(long now, double horizontal, double dy, boolean collision) {
        return MoveContext.builder()
                .time(now, now * 1000000L)
                .from(0, 64, 0)
                .to(horizontal, 64 + dy, 0)
                .geometry(geometry(collision))
                .build();
    }

    @Test
    void sustainedLowJitterHoverInFreeAirIsDetected() {
        PlayerData d = data();
        for (int i = 0; i < 80; i++)
            checks.checkFly(null, d, position(20000 + i * 50, 0.005, 0, false), false);
        assertTrue(flags.contains("Fly A"));
        assertEquals(0, kickIncidents);
    }

    @Test
    void verticalOscillationCannotBypassTheSustainedHoverBand() {
        PlayerData d = data();
        double y = 64;
        for (int i = 0; i < 80; i++) {
            double nextY = i % 2 == 0 ? 64.05 : 63.95;
            MoveContext m =
                    MoveContext.builder()
                            .time(20000 + i * 50, 0)
                            .from(0, y, 0)
                            .to(0.005, nextY, 0)
                            .geometry(geometry(false))
                            .build();
            checks.checkFly(null, d, m, false);
            y = nextY;
        }
        assertTrue(flags.contains("Fly A"));
        assertEquals(0, kickIncidents);
    }

    @Test
    void serverDisabledGravityDoesNotProduceStationaryHoverEvidence() {
        PlayerData d = data();
        for (int i = 0; i < 80; i++) {
            MoveContext m =
                    MoveContext.builder()
                            .time(20000 + i * 50, 0)
                            .from(0, 64, 0)
                            .to(0.005, 64, 0)
                            .gravityAttr(0)
                            .geometry(geometry(false))
                            .build();
            checks.checkFly(null, d, m, false);
        }
        assertTrue(flags.isEmpty());
    }

    @Test
    void packetBurstAtAJumpApexDoesNotEstablishAStationaryHover() {
        PlayerData d = data();
        for (int i = 0; i < 200; i++)
            checks.checkFly(null, d, position(20000, 0.005, 0, false), false);
        assertTrue(flags.isEmpty());
    }

    @Test
    void missingSamplesCannotBeTreatedAsContinuousHoverEvidence() {
        PlayerData d = data();
        for (int i = 0; i < 80; i++)
            checks.checkFly(null, d, position(20000 + i * 500, 0.005, 0, false), false);
        assertTrue(flags.isEmpty());
    }

    @Test
    void nearbySupportOrHardEntityCollisionPreventsLowJitterHoverEvidence() {
        PlayerData d = data();
        for (int i = 0; i < 80; i++)
            checks.checkFly(null, d, position(20000 + i * 50, 0.005, 0, true), false);
        assertTrue(flags.isEmpty());
    }

    @Test
    void physicalJumpAndFallDoNotTriggerLowJitterHoverRule() {
        PlayerData d = data();
        double y = 64;
        double velocity = 0.42;
        for (int i = 0; i < 60; i++) {
            MoveContext m =
                    MoveContext.builder()
                            .time(20000 + i * 50, 0)
                            .from(0, y, 0)
                            .to(0.005, y + velocity, 0)
                            .geometry(geometry(false))
                            .build();
            checks.checkFly(null, d, m, false);
            y += velocity;
            velocity = (velocity - 0.08) * 0.98;
        }
        assertTrue(flags.isEmpty());
    }

    private void landAfterFall(PlayerData d, boolean heldMace) {
        d.fallPeakY = 70;
        MoveContext landing =
                MoveContext.builder()
                        .time(20000, 0)
                        .from(0, 70, 0)
                        .to(0, 64, 0)
                        .holdingMace(heldMace)
                        .geometry(geometry(false))
                        .build();
        checks.checkNoFall(null, d, landing, true, false);
        MovementChecks.rememberMove(d, 64, true, 20000);
        MoveContext afterLanding =
                MoveContext.builder()
                        .time(20800, 0)
                        .from(0, 64, 0)
                        .to(0, 64, 0)
                        .holdingMace(heldMace)
                        .geometry(geometry(false))
                        .build();
        checks.checkNoFall(null, d, afterLanding, true, false);
    }

    @Test
    void holdingMaceAloneCannotBypassNoFall() {
        PlayerData d = data();
        landAfterFall(d, true);
        assertTrue(flags.contains("NoFall A"));
    }

    @Test
    void confirmedRecentMaceSmashExplainsMissingFallDamage() {
        PlayerData d = data();
        d.lastMaceSmashAt = 19950;
        landAfterFall(d, true);
        assertTrue(flags.isEmpty());
    }

    @Test
    void oldMaceSmashDoesNotExemptALaterUnrelatedFall() {
        PlayerData d = data();
        d.lastMaceSmashAt = 10000;
        landAfterFall(d, true);
        assertTrue(flags.contains("NoFall A"));
    }

    @Test
    void tinyActualPositionUpdatesCannotBypassTimer() {
        PlayerData d = data();
        for (int i = 0; i < 40; i++)
            checks.checkTimer(null, d, position(20000 + i * 10, 0.005, 0, false));
        assertTrue(flags.contains("Timer A"));
    }

    @Test
    void vanillaPositionRateWithInterleavedRotationsDoesNotTriggerTimer() {
        PlayerData d = data();
        for (int i = 0; i < 80; i++) {
            long now = 20000 + i * 50;
            checks.checkTimer(null, d, position(now, 0.005, 0, false));
            for (int j = 1; j < 5; j++)
                checks.checkTimer(null, d, position(now + j * 5, 0, 0, false));
        }
        assertTrue(flags.isEmpty());
        assertEquals(0.0, d.timerBalance);
    }

    @Test
    void rotationFloodDoesNotCountAsExtraPhysicsTicks() {
        PlayerData d = data();
        d.lastMoveNanos = 19950000000L;
        for (int i = 0; i < 100; i++) checks.checkTimer(null, d, position(20000 + i, 0, 0, false));
        assertTrue(flags.isEmpty());
        assertEquals(19950000000L, d.lastMoveNanos);
        assertEquals(0.0, d.timerBalance);
    }
}
