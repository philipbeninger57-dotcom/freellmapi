package de.kyrosmp.anticheat.checks.movement;

import static org.junit.jupiter.api.Assertions.*;

import de.kyrosmp.anticheat.core.*;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class InstantPreventionTest {
    int setbacks;
    int flags;
    Location target;
    final FlagSink sink =
            new FlagSink() {
                public String flag(Player p, PlayerData d, String c, String r, String details) {
                    flags++;
                    return null;
                }

                public void action(String id, String action) {}
            };
    final SetbackService engine =
            new SetbackService() {
                public boolean setback(Player p, PlayerData d, String id) {
                    setbacks++;
                    return false;
                }

                public boolean setbackTo(Player p, PlayerData d, Location to, String id) {
                    setbacks++;
                    target = to;
                    return false;
                }

                public void registerFlyPunishment(Player p, PlayerData d, long now, String id) {
                    fail("No automatic punishment");
                }
            };
    final MovementChecks checks = new MovementChecks(sink, engine, CheckConfig.defaults());
    final PlayerData d = new PlayerData(UUID.randomUUID(), "Test");

    static MoveGeometry geo(boolean water) {
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
                return false;
            }

            public boolean safeNoDamageLanding() {
                return false;
            }

            public boolean liquidUnderFeet() {
                return water;
            }

            public boolean bodyWet() {
                return false;
            }

            public boolean collisionNearby() {
                return false;
            }
        };
    }

    static MoveContext motion(long now, double dx, double fromY, double toY, boolean water) {
        return MoveContext.builder()
                .time(now, now * 1_000_000)
                .from(0, fromY, 0)
                .to(dx, toY, 0)
                .geometry(geo(water))
                .build();
    }

    @Test
    void impossibleHeightStopsBeforeFlagThresholdAndWithoutFlagId() {
        d.groundY = 64;
        checks.checkFly(null, d, motion(20_000, .01, 68, 68.2, false), false);
        assertTrue(d.preventMove);
        assertTrue(setbacks > 0);
        assertEquals(0, flags);
    }

    @Test
    void continuedAscentIsRejectedWithoutWaitingForSeveralIncidents() {
        d.groundY = 64;
        d.airTicks = 20;
        d.airSince = 19_000;
        checks.checkFly(null, d, motion(20_000, .01, 64, 64.2, false), false);
        assertTrue(d.preventMove);
        assertEquals(0, flags);
    }

    @Test
    void slowWaterWalkingStopsOnFirstConfirmedWindow() {
        for (int i = 0; i < 8; i++)
            checks.checkJesus(null, d, motion(20_000 + i * 50, .005, 64, 64, true), false);
        assertTrue(d.preventMove);
        assertTrue(setbacks > 0);
        assertEquals(0, flags);
    }

    @Test
    void singleWaterApexAndPacketBurstAreNotProof() {
        for (int i = 0; i < 50; i++)
            checks.checkJesus(null, d, motion(20_000, .005, 64, 64, true), false);
        assertFalse(d.preventMove);
        assertEquals(0, setbacks);
    }

    @Test
    void excessSpeedIsRejectedOnFirstMove() {
        assertTrue(checks.checkSpeed(null, d, motion(20_000, 5, 64, 64, false), true));
        assertTrue(d.preventMove);
    }

    @Test
    void timerStaysAlertOnlyEvenWhenInstantPreventionIsEnabled() {
        for (int i = 0; i < 40; i++) {
            checks.checkTimer(null, d, motion(20_000 + i * 10, .005, 64, 64, false));
        }
        assertTrue(flags > 0, "excess movement rate must still alert staff");
        assertFalse(d.preventMove, "Timer must never cancel movement");
        assertEquals(0, setbacks, "Timer must never request a setback");
        assertTrue(
                d.timerBalance <= CheckConfig.defaults().timerMaxBalanceMs(),
                "reporting resets the alert window instead of preserving enforcement debt");
    }

    @Test
    void disabledTimerDoesNotFlagOrPreventFastMovement() {
        org.bukkit.configuration.file.YamlConfiguration config =
                new org.bukkit.configuration.file.YamlConfiguration();
        config.set("checks.timer.enabled", false);
        checks.configure(CheckConfig.from(config));
        for (int i = 0; i < 40; i++) {
            checks.checkTimer(null, d, motion(20_000 + i * 10, .005, 64, 64, false));
        }
        assertEquals(0, flags);
        assertFalse(d.preventMove);
        assertEquals(0, setbacks);
    }

    @Test
    void groundSpoofIsPreventedEvenWhenNoFlagIsStored() {
        MoveContext m =
                MoveContext.builder()
                        .time(20_000, 0)
                        .from(0, 64, 0)
                        .to(.1, 64, 0)
                        .clientOnGround(true)
                        .geometry(geo(false))
                        .build();
        for (int i = 0; i < 4; i++) checks.checkNoFall(null, d, m, false, false);
        assertTrue(d.preventMove);
        assertTrue(setbacks > 0);
    }

    @Test
    void normalTwentyHzMovementAndBallisticJumpDoNotTriggerPrevention() {
        double y = 64, vy = .42;
        d.groundY = 64;
        for (int i = 0; i < 40; i++) {
            MoveContext m = motion(20_000 + i * 50, .2, y, y + vy, false);
            checks.checkTimer(null, d, m);
            checks.checkFly(null, d, m, false);
            y += vy;
            vy = (vy - .08) * .98;
        }
        assertFalse(d.preventMove);
        assertEquals(0, setbacks);
    }

    @Test
    void globalOffKeepsAlertsButDisablesMovementPrevention() {
        checks.withPrevention(new PreventionConfig(false, true, true, true, true));
        assertFalse(checks.checkSpeed(null, d, motion(20_000, 5, 64, 64, false), true));
        assertFalse(d.preventMove);
        assertEquals(0, setbacks);
        assertTrue(flags > 0);
    }

    @Test
    void elytraClimbIsStoppedBeforeAlertBufferOrIncidentCount() {
        ElytraCheck elytra = new ElytraCheck(sink, engine, CheckConfig.defaults());
        d.elytraCeilingMin = 64;
        MoveContext m =
                MoveContext.builder()
                        .time(20_000, 0)
                        .from(0, 70, 0)
                        .to(.2, 71, 0)
                        .gliding(true)
                        .geometry(geo(false))
                        .build();
        elytra.check(null, d, m);
        assertTrue(d.preventMove);
        assertEquals(0, flags);
        assertTrue(setbacks > 0);
    }

    @Test
    void movingApexSamplesArrivingInOneLagBurstDoNotProveHover() {
        d.groundY = 64;
        for (int i = 0; i < 80; i++)
            checks.checkFly(null, d, motion(20_000, .2, 64, 64, false), false);
        assertFalse(d.preventMove);
    }

    @Test
    void teleportAndKnockbackGraceKeepLegitimateWaterCrossingPossible() {
        d.lastTeleport = 19_500;
        for (int i = 0; i < 8; i++)
            checks.checkJesus(null, d, motion(20_000 + i * 50, .1, 64, 64, true), false);
        assertFalse(d.preventMove);
        d.lastTeleport = 0;
        d.velocityGraceUntil = 25_000;
        for (int i = 0; i < 8; i++)
            checks.checkJesus(null, d, motion(21_000 + i * 50, .1, 64, 64, true), false);
        assertFalse(d.preventMove);
    }

    @Test
    void confirmedNoFallUsesAnchorBeforeFallInsteadOfTheLandingPosition() {
        d.lastGround = true;
        d.lastSafe = new Location(null, 0, 90, 0);
        d.fallPeakY = 90;
        checks.checkNoFall(null, d, motion(20_000, .1, 90, 89, false), false, false);
        d.lastGround = false;
        checks.checkNoFall(null, d, motion(21_000, .1, 65, 64, false), true, false);
        d.lastGround = true;
        d.lastSafe = new Location(null, 10, 64, 0);
        checks.checkNoFall(null, d, motion(21_800, .1, 64, 64, false), true, false);
        assertTrue(d.preventMove);
        assertNotNull(target);
        assertEquals(90, target.getY());
    }
}
