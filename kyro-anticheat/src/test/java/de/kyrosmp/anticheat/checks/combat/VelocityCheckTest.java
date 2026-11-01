package de.kyrosmp.anticheat.checks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;

import org.bukkit.GameMode;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class VelocityCheckTest {
    private static Player player(AtomicInteger scheduled) {
        EntityScheduler scheduler =
                (EntityScheduler)
                        Proxy.newProxyInstance(
                                EntityScheduler.class.getClassLoader(),
                                new Class<?>[] {EntityScheduler.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("runDelayed")) {
                                        scheduled.incrementAndGet();
                                        return null;
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        return (Player)
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getGameMode" -> GameMode.SURVIVAL;
                                    case "getScheduler" -> scheduler;
                                    case "getPing" -> 0;
                                    case "isDead",
                                            "getAllowFlight",
                                            "isFlying",
                                            "isGliding",
                                            "isInsideVehicle",
                                            "isSwimming",
                                            "isRiptiding" ->
                                            false;
                                    default ->
                                            throw new UnsupportedOperationException(
                                                    method.getName());
                                });
    }

    @Test
    void cachedBedrockIdentityDoesNotOverrideDisabledExemption() {
        AtomicInteger scheduled = new AtomicInteger();
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("bedrock.exempt", false);
        VelocityCheck check = new VelocityCheck(null, null, CheckConfig.from(config));
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        data.bedrock = true;
        check.onKnockback(player(scheduled), data, new Vector(0.5, 0.4, 0), 10_000);
        assertEquals(1, scheduled.get());
        assertEquals(10_000, data.kbTime);
    }

    @Test
    void enabledBedrockExemptionStillSkipsVelocityCheck() {
        AtomicInteger scheduled = new AtomicInteger();
        VelocityCheck check = new VelocityCheck(null, null, CheckConfig.defaults());
        PlayerData data = new PlayerData(UUID.randomUUID(), "test");
        data.bedrock = true;
        check.onKnockback(player(scheduled), data, new Vector(0.5, 0.4, 0), 10_000);
        assertEquals(0, scheduled.get());
        assertEquals(0, data.kbTime);
    }
}
