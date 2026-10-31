package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.checks.action.BlockBreakChecksAccess;
import de.kyrosmp.anticheat.checks.action.BlockPlaceChecks;
import de.kyrosmp.anticheat.checks.action.BlockPlaceChecksAccessHelper;
import de.kyrosmp.anticheat.checks.action.InventoryMoveCheck;
import de.kyrosmp.anticheat.checks.combat.AutoClickerCheck;
import de.kyrosmp.anticheat.checks.movement.HoverCheckAccess;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 3.7 prevention: Fly C heartbeat, Nuker/GhostHand, Scaffold B, click and inventory prevention. */
class StrictPreventionTest {
    final List<String> flags = new ArrayList<>();
    int setbacks;
    final FlagSink sink = new FlagSink() {
        public String flag(Player p, PlayerData d, String check, String reason, String details) { flags.add(check); return null; }
        public void action(String id, String action) { }
    };
    final SetbackService engine = new SetbackService() {
        public boolean setback(Player p, PlayerData d, String id) { setbacks++; return true; }
        public boolean setbackTo(Player p, PlayerData d, Location to, String id) { setbacks++; return true; }
        public void registerFlyPunishment(Player p, PlayerData d, long now, String id) { fail("no automatic punishment"); }
    };
    final PlayerData d = new PlayerData(UUID.randomUUID(), "test");

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Function<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            Object v = values.apply(m.getName());
            if (v != null) return v;
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == int.class) return 0;
            if (m.getReturnType() == long.class) return 0L;
            if (m.getReturnType() == double.class) return 0.0;
            if (m.getReturnType() == float.class) return 0.0f;
            return null;
        });
    }

    Player survivalPlayer(boolean flying) {
        return proxy(Player.class, n -> switch (n) {
            case "isOnline", "hasGravity" -> true;
            case "getAllowFlight" -> flying;
            case "getGameMode" -> GameMode.SURVIVAL;
            case "getEyeLocation" -> new Location(null, 0.5, 65.62, 0.5);
            case "getPing" -> 50;
            default -> null;
        });
    }

    // ---------------------------------------------------------------- Fly C

    @Test void stillInMidAirIsCorrectedWithoutTreatingClientSilenceAsCheating() {
        var hover = HoverCheckAccess.create(sink, engine, false);
        Player p = survivalPlayer(false);
        d.lastPositionChangeAt = 10_000;
        assertFalse(HoverCheckAccess.tick(hover, p, d, 11_000));   // still moving recently
        assertFalse(HoverCheckAccess.tick(hover, p, d, 11_400));   // first confirmation
        assertTrue(HoverCheckAccess.tick(hover, p, d, 11_650));    // second confirmation
        assertEquals(1, setbacks);
        assertTrue(flags.isEmpty(), "position silence alone is not cheat evidence");
        assertEquals(0.0, d.hoverBuffer);
    }

    @Test void supportedPlayersFlightAndTeleportGraceAreNeverJudged() {
        Player p = survivalPlayer(false);
        d.lastPositionChangeAt = 10_000;
        var supported = HoverCheckAccess.create(sink, engine, true);
        for (int i = 0; i < 10; i++) assertFalse(HoverCheckAccess.tick(supported, p, d, 12_000 + i * 250));
        var air = HoverCheckAccess.create(sink, engine, false);
        Player flyer = survivalPlayer(true);
        for (int i = 0; i < 10; i++) assertFalse(HoverCheckAccess.tick(air, flyer, d, 12_000 + i * 250));
        d.lastTeleport = 14_000;
        for (int i = 0; i < 10; i++) assertFalse(HoverCheckAccess.tick(air, p, d, 14_100 + i * 250));
        assertEquals(0, setbacks);
    }

    // ---------------------------------------------------------------- Nuker / GhostHand

    Block block(int x, int y, int z) {
        return proxy(Block.class, n -> switch (n) {
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getType" -> Material.STONE;
            default -> null;
        });
    }

    @Test void nukerCancelsDigsAboveTheRateAVanillaClientCanReach() {
        var checks = BlockBreakChecksAccess.create(sink, StrictConfig.defaults(), false);
        Player p = survivalPlayer(false);
        int cancelled = 0;
        for (int i = 0; i < 20; i++) if (checks.onDigStart(p, d, block(i, 64, 0), 20_000 + i * 50L)) cancelled++;
        assertEquals(0, cancelled, "20 instant breaks per second are vanilla");
        for (int i = 0; i < 40; i++) if (checks.onDigStart(p, d, block(i, 65, 0), 21_000 + i * 5L)) cancelled++;
        assertTrue(cancelled >= 14, "cancelled " + cancelled);
    }

    @Test void ghostHandCancelsDiggingThroughWalls() {
        Player p = survivalPlayer(false);
        assertTrue(BlockBreakChecksAccess.create(sink, StrictConfig.defaults(), true).onDigStart(p, d, block(0, 64, 3), 20_000));
        assertFalse(BlockBreakChecksAccess.create(sink, StrictConfig.defaults(), false).onDigStart(p, d, block(0, 64, 3), 30_000));
    }

    // ---------------------------------------------------------------- Scaffold B

    @Test void placingAgainstAFaceTheEyeIsBehindIsCancelled() {
        BlockPlaceChecks checks = new BlockPlaceChecks(sink, CheckConfig.defaults());
        Player p = survivalPlayer(false);
        BlockPlaceChecksAccessHelper.setFace(checks, -0.6);
        BlockPlaceChecksAccessHelper.setSolid(checks, false);
        assertTrue(checks.onPlace(p, d, block(1, 64, 0), block(0, 64, 0), 20_000));
        BlockPlaceChecksAccessHelper.setFace(checks, -0.05);
        assertFalse(checks.onPlace(p, d, block(1, 64, 0), block(0, 64, 0), 21_000));
        // replacing a block (not adjacent) is never judged
        BlockPlaceChecksAccessHelper.setFace(checks, -5);
        assertFalse(checks.onPlace(p, d, block(0, 64, 0), block(0, 64, 0), 22_000));
    }

    // ---------------------------------------------------------------- clicks and inventory

    @Test void hitsAreRefusedOnlyWhileTheClickRateIsAboveMaxCps() {
        AutoClickerCheck check = new AutoClickerCheck(sink, CheckConfig.defaults());
        for (int i = 0; i < 25; i++) check.onSwing(null, d, 30_000 + i * 40L);
        assertFalse(check.shouldCancelHit(d, 31_000));
        for (int i = 0; i < 40; i++) check.onSwing(null, d, 32_000 + i * 20L);
        assertTrue(check.shouldCancelHit(d, 32_800));
        assertFalse(check.shouldCancelHit(d, 35_000));
        check.withPolicy(StrictConfig.defaults(), new PreventionConfig(true, true, true, true, false));
        for (int i = 0; i < 40; i++) check.onSwing(null, d, 40_000 + i * 20L);
        assertFalse(check.shouldCancelHit(d, 40_800));
    }

    @Test void secondInventoryClickWhileClearlyMovingIsRefused() {
        InventoryMoveCheck check = new InventoryMoveCheck(sink, CheckConfig.defaults());
        Player p = survivalPlayer(false);
        check.onOpen(d, 49_000);
        d.lastGround = true;
        d.lastMoveHorizontal = 0.28;
        d.prevMoveHorizontal = 0.28;
        d.lastMove = 50_000;
        assertFalse(check.onClick(p, d, 50_050));
        d.lastMove = 50_075;
        assertTrue(check.onClick(p, d, 50_100));
        d.lastMoveHorizontal = 0.1;      // momentum decays after the screen opened
        d.inventoryMoveBuffer = 0;
        assertFalse(check.onClick(p, d, 50_120));
        assertFalse(check.onClick(p, d, 50_140));
    }

    // ---------------------------------------------------------------- config and geometry

    @Test void strictConfigUsesDefaultsAndClampsUnsafeValues() {
        YamlConfiguration yaml = new YamlConfiguration();
        assertEquals(StrictConfig.defaults(), StrictConfig.from(yaml));
        yaml.set("checks.fly.prediction.tolerance", 99.0);
        yaml.set("checks.fly.hover.still-ms", 1);
        yaml.set("checks.nuker.max-per-second", 1_000_000);
        StrictConfig c = StrictConfig.from(yaml);
        assertEquals(0.5, c.flyPredictionTolerance());
        assertEquals(600L, c.hoverStillMs());
        assertEquals(200, c.nukerMaxPerSecond());
    }

    @Test void blockCategoriesSeparateLiquidsClimbablesAndSlowingBlocks() {
        assertEquals(Collisions.LIQUID, Collisions.category("WATER", true));
        assertEquals(Collisions.LIQUID, Collisions.category("BUBBLE_COLUMN", false));
        assertEquals(Collisions.CLIMBABLE, Collisions.category("LADDER", false));
        assertEquals(Collisions.CLIMBABLE, Collisions.category("TWISTING_VINES_PLANT", false));
        assertEquals(Collisions.STUCK, Collisions.category("COBWEB", false));
        assertEquals(Collisions.STUCK, Collisions.category("HONEY_BLOCK", false));
        assertEquals(0, Collisions.category("STONE", false));
    }
}
