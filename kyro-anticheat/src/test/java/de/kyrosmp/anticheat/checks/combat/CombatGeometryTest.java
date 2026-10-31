package de.kyrosmp.anticheat.checks.combat;

import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.AttributeSet;
import de.kyrosmp.anticheat.flag.FlagSink;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatGeometryTest {
    @Test
    void overlappingTargetsDoNotInventAOneHundredEightyDegreeSwitch() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        data.lastAttackDirX = 1;
        assertEquals(0, CombatChecks.separationDegrees(data, null));
    }

    @Test
    void missingPreviousDirectionDoesNotCountAsAWideSwitch() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        assertEquals(0, CombatChecks.separationDegrees(data, new Vector(1, 0, 0)));
    }

    @Test
    void knownOppositeDirectionsAreStillRecognized() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        data.lastAttackDirX = 1;
        assertEquals(180, CombatChecks.separationDegrees(data, new Vector(-1, 0, 0)));
    }

    @Test
    void rejectedExtremeReachSkipsExpensiveWallTraces() {
        UUID targetId = UUID.randomUUID();
        Player attacker = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEyeLocation" -> new Location(null, 0, 1.62, 0);
                    case "getPing" -> 0;
                    case "getInventory" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        LivingEntity target = (LivingEntity) Proxy.newProxyInstance(LivingEntity.class.getClassLoader(), new Class<?>[]{LivingEntity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBoundingBox" -> new BoundingBox(8, 0, 0, 8.6, 1.8, 0.6);
                    case "getVelocity" -> new Vector();
                    case "getUniqueId" -> targetId;
                    case "getLocation" -> new Location(null, 8, 0, 0);
                    case "getType" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        FlagSink sink = new FlagSink() {
            @Override public String flag(Player p, PlayerData d, String check, String reason, String details) { return "flag"; }
            @Override public void action(String id, String action) { }
        };
        CombatChecks checks = new CombatChecks(sink, CheckConfig.defaults(), AttributeSet.NONE);
        AtomicInteger worldQueries = new AtomicInteger();
        checks.regionOwned = loc -> {
            worldQueries.incrementAndGet();
            return false;
        };
        PlayerData d = new PlayerData(UUID.randomUUID(), "test");
        assertTrue(checks.onAttack(attacker, d, target, null, 10_000).cancel());
        assertEquals(0, worldQueries.get());
    }
}
