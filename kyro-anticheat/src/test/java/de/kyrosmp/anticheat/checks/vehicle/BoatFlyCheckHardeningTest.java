package de.kyrosmp.anticheat.checks.vehicle;

import static org.junit.jupiter.api.Assertions.*;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

class BoatFlyCheckHardeningTest {
    private int flags;
    private final FlagSink sink =
            new FlagSink() {
                public String flag(
                        Player p, PlayerData d, String check, String reason, String details) {
                    assertEquals("BoatFly A", check);
                    flags++;
                    return null;
                }

                public void action(String id, String action) {}
            };
    private final BoatFlyCheck check = new BoatFlyCheck(sink, null, CheckConfig.defaults());
    private final PlayerData data = new PlayerData(UUID.randomUUID(), "Tester");

    private void move(double dx, double dy, boolean supported, long now) {
        check.evaluate(
                null,
                data,
                null,
                new Location(null, 0, 80, 0),
                new Location(null, dx, 80 + dy, 0),
                supported,
                now);
    }

    @Test
    void unsupportedStationaryHoverCreatesEvidence() {
        for (int tick = 0; tick < 35; tick++) move(0, 0, false, 20000 + tick * 50);
        assertTrue(flags > 0);
    }

    @Test
    void unsupportedSlowHorizontalHoverCreatesEvidence() {
        for (int tick = 0; tick < 35; tick++) move(0.05, 0, false, 20000 + tick * 50);
        assertTrue(flags > 0);
    }

    @Test
    void sameTickMovementBurstAtApexDoesNotFlag() {
        for (int packet = 0; packet < 100; packet++) move(0, 0, false, 20000);
        assertEquals(0, flags);
    }

    @Test
    void intermittentSamplesAcrossLongGapsDoNotFormContinuousHover() {
        for (int tick = 0; tick < 20; tick++) move(0, 0, false, 20000 + tick * 500);
        assertEquals(0, flags);
    }

    @Test
    void realFallingMotionBreaksTheContinuousHoverInterval() {
        for (int tick = 0; tick < 12; tick++) move(0, 0, false, 20000 + tick * 50);
        move(0, -0.1, false, 20600);
        for (int tick = 0; tick < 12; tick++) move(0, 0, false, 20650 + tick * 50);
        assertEquals(0, flags);
    }

    @Test
    void serverDisabledGravityExcludesPluginCreatedFloatingBoats() {
        AtomicBoolean queriedGeometry = new AtomicBoolean();
        UUID boatId = UUID.randomUUID();
        Boat boat =
                (Boat)
                        Proxy.newProxyInstance(
                                Boat.class.getClassLoader(),
                                new Class<?>[] {Boat.class},
                                (p, method, args) ->
                                        switch (method.getName()) {
                                            case "getUniqueId" -> boatId;
                                            case "hasGravity" -> false;
                                            case "getBoundingBox" -> {
                                                queriedGeometry.set(true);
                                                throw new AssertionError(
                                                        "Gravity-disabled boat should not need"
                                                            + " collision queries");
                                            }
                                            default -> null;
                                        });
        assertDoesNotThrow(
                () ->
                        check.check(
                                null,
                                data,
                                boat,
                                new Location(null, 0, 80, 0),
                                new Location(null, 0, 80, 0),
                                20000));
        assertFalse(queriedGeometry.get());
        assertEquals(0, flags);
    }

    @Test
    void ordinaryBallisticArcAndBriefApexDoNotFlag() {
        double dy = 0.4;
        for (int tick = 0; tick < 30; tick++) {
            move(0, dy, false, 20000 + tick * 50);
            dy -= 0.04;
        }
        assertEquals(0, flags);
    }

    @Test
    void supportedStationaryBoatDoesNotFlag() {
        for (int tick = 0; tick < 100; tick++) move(0, 0, true, 20000 + tick * 50);
        assertEquals(0, flags);
    }

    @Test
    void velocityGraceStillSkipsHoverEvidence() {
        data.velocityGraceUntil = 30000;
        for (int tick = 0; tick < 100; tick++) move(0, 0, false, 20000 + tick * 50);
        assertEquals(0, flags);
    }

    @Test
    void leavingSupportRetainsExistingGrace() {
        move(0, 0, true, 20000);
        for (int tick = 1; tick <= 5; tick++) move(0, 0, false, 20000 + tick * 50);
        assertEquals(0, flags);
        assertEquals(0.0, data.boatFlyBuffer);
    }

    @Test
    void provenAscentRequestsOriginalAnchorBeforeFlagAndDoesNotAdvanceIt() {
        org.bukkit.World w =
                (org.bukkit.World)
                        Proxy.newProxyInstance(
                                org.bukkit.World.class.getClassLoader(),
                                new Class<?>[] {org.bukkit.World.class},
                                (p, m, a) -> null);
        Boat b =
                (Boat)
                        Proxy.newProxyInstance(
                                Boat.class.getClassLoader(),
                                new Class<?>[] {Boat.class},
                                (p, m, a) -> null);
        data.lastBoatSafe = new Location(w, 0, 64, 0);
        data.lastBoatSafeAt = 10000;
        java.util.List<Location> targets = new java.util.ArrayList<>();
        check.applySetback =
                (boat, target) -> {
                    targets.add(target.clone());
                    return true;
                };
        for (int i = 0; i < 15; i++)
            check.evaluate(
                    null,
                    data,
                    b,
                    new Location(w, i, 70, 0),
                    new Location(w, i + 1, 70.2, 0),
                    false,
                    20000 + i * 50);
        assertTrue(data.preventMove);
        assertFalse(targets.isEmpty());
        assertEquals(0.0, targets.getFirst().getX());
        assertTrue(targets.stream().allMatch(t -> t.getY() == 64 && t.getX() == 0));
        assertTrue(targets.size() <= 2, "In-flight cooldown prevents teleport spam");
    }

    @Test
    void unsupportedFirstObservationNeverInventsAnAnchorFromCheatingPosition() {
        move(1, .4, false, 20000);
        assertNull(data.lastBoatSafe);
    }
}
