package de.kyrosmp.anticheat.checks.action;

import static org.junit.jupiter.api.Assertions.*;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.GameMode;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

class BedrockExemptionTest {
    private int flags;
    private final FlagSink sink =
            new FlagSink() {
                public String flag(Player p, PlayerData d, String c, String r, String details) {
                    flags++;
                    return null;
                }

                public void action(String id, String a) {}
            };

    private Player player() {
        return (Player)
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (p, m, a) -> {
                            if (m.getName().equals("getGameMode")) return GameMode.SURVIVAL;
                            if (m.getReturnType() == boolean.class) return false;
                            return null;
                        });
    }

    private PlayerData movingBedrockPlayer() {
        PlayerData d = new PlayerData(UUID.randomUUID(), ".Tester");
        d.bedrock = true;
        d.lastMove = 20000;
        d.lastGround = true;
        d.lastMoveHorizontal = d.prevMoveHorizontal = 0.3;
        return d;
    }

    @Test
    void disablingExemptionEnablesInventoryChecksWithCachedBedrockIdentity() {
        MemoryConfiguration yaml = new MemoryConfiguration();
        yaml.set("bedrock.exempt", false);
        InventoryMoveCheck check = new InventoryMoveCheck(sink, CheckConfig.from(yaml));
        PlayerData d = movingBedrockPlayer();
        check.onOpen(d, 19_000);
        for (int i = 0; i < 4; i++) {
            d.lastMove = 20_000 + i * 50L;
            check.onClick(player(), d, d.lastMove);
        }
        assertEquals(1, flags);
    }

    @Test
    void enabledExemptionSkipsInventoryChecks() {
        InventoryMoveCheck check = new InventoryMoveCheck(sink, CheckConfig.defaults());
        PlayerData d = movingBedrockPlayer();
        for (int i = 0; i < 8; i++) check.onClick(player(), d, 20000);
        assertEquals(0, flags);
    }

    @Test
    void xraySkipsExemptPlayerBeforeCollectingBlockEvidence() {
        // Cached identity is sufficient; no world or block API should be accessed.
        XRayCheck check = new XRayCheck(sink, CheckConfig.defaults());
        assertDoesNotThrow(() -> check.onBreak(null, movingBedrockPlayer(), null, 20000));
        assertEquals(0, flags);
    }
}
