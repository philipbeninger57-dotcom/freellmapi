package de.kyrosmp.anticheat.listener;

import static org.junit.jupiter.api.Assertions.*;

import de.kyrosmp.anticheat.checks.combat.VelocityCheck;
import de.kyrosmp.anticheat.checks.movement.ElytraCheck;
import de.kyrosmp.anticheat.checks.movement.MovementChecks;
import de.kyrosmp.anticheat.core.*;
import de.kyrosmp.anticheat.debug.DebugManager;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

class MovementListenerRegressionTest {
    private static final UUID ID = UUID.randomUUID();
    private static final CheckConfig CONFIG = CheckConfig.defaults();
    private static final FlagSink SINK =
            new FlagSink() {
                public String flag(
                        Player p, PlayerData d, String check, String reason, String details) {
                    return "flag";
                }

                public void action(String id, String action) {}
            };
    private static final SetbackService SETBACK =
            new SetbackService() {
                public boolean setback(Player p, PlayerData d, String id) {
                    return false;
                }

                public boolean setbackTo(Player p, PlayerData d, Location at, String id) {
                    return false;
                }

                public void registerFlyPunishment(Player p, PlayerData d, long now, String id) {}
            };

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (p, m, args) -> {
                            Object value = values.apply(m.getName());
                            if (value != null) return value;
                            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                            if (m.getName().equals("equals")) return p == args[0];
                            if (m.getReturnType() == boolean.class) return false;
                            if (m.getReturnType() == int.class) return 0;
                            if (m.getReturnType() == long.class) return 0L;
                            if (m.getReturnType() == double.class) return 0.0;
                            if (m.getReturnType() == float.class) return 0.0f;
                            return null;
                        });
    }

    private final World world = proxy(World.class, n -> n.equals("getName") ? "world" : null);
    private final Player player =
            proxy(
                    Player.class,
                    n ->
                            switch (n) {
                                case "getUniqueId" -> ID;
                                case "getName" -> "Tester";
                                case "getWorld" -> world;
                                default -> null;
                            });
    private final MoveGeometry ground =
            proxy(MoveGeometry.class, n -> n.equals("ground") || n.equals("nearGround"));

    private MovementListener listener(PlayerDataStore store) {
        MovementChecks moves = new MovementChecks(SINK, SETBACK, CONFIG);
        MovementListener.Checks checks =
                new MovementListener.Checks(
                        moves,
                        null,
                        new VelocityCheck(null, SINK, CONFIG),
                        null,
                        new ElytraCheck(SINK, SETBACK, CONFIG));
        return new MovementListener(
                store,
                new ExemptionManager(CONFIG),
                SINK,
                new DebugManager(null, 2),
                () -> checks,
                () -> CONFIG,
                AttributeSet.NONE,
                (p, from, to, now, nano, a) ->
                        MoveContext.builder()
                                .time(now, nano)
                                .world(world)
                                .from(from.getX(), from.getY(), from.getZ())
                                .to(to.getX(), to.getY(), to.getZ())
                                .geometry(ground)
                                .build(),
                () -> 20000L);
    }

    private PlayerData initialize(PlayerDataStore store, Location from) {
        PlayerData d = store.getQuiet(player);
        d.bedrock = false;
        d.positionInitialized = true;
        d.lastGround = true;
        d.groundY = from.getY();
        d.lastSafe = from.clone();
        d.lastSafeAt = 10000;
        d.setSync(from, 19950);
        return d;
    }

    @Test
    void rejectsMalformedRedirectBeforeItCanPoisonSynchronization() {
        PlayerDataStore store = new PlayerDataStore();
        Location from = new Location(world, 0, 64, 0);
        PlayerData d = initialize(store, from);
        MovementListener listener = listener(store);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, new Location(world, 1, 64, 0));
        listener.onMoveLowest(event);
        event.setTo(new Location(world, Double.NaN, 64, 0));
        listener.handleMove(event, 20000L, 20000000000L);
        assertTrue(event.isCancelled());
        assertEquals(0.0, event.getTo().getX());
        assertEquals(0.0, d.syncX);
        assertEquals(0, d.pluginRedirects);
        assertEquals(0, d.teleportsApplied);
    }

    @Test
    void rejectedSpeedMoveDoesNotReplaceTheSafeSetbackAnchor() {
        PlayerDataStore store = new PlayerDataStore();
        Location from = new Location(world, 0, 64, 0);
        PlayerData d = initialize(store, from);
        MovementListener listener = listener(store);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, new Location(world, 5, 64, 0));
        listener.onMoveLowest(event);
        listener.handleMove(event, 20000L, 20000000000L);
        assertEquals(0.0, event.getTo().getX());
        assertEquals(0.0, d.lastSafe.getX());
        assertEquals(10000, d.lastSafeAt);
    }

    @Test
    void speedRejectionDoesNotCreateATimerAccountingHole() {
        PlayerDataStore store = new PlayerDataStore();
        Location from = new Location(world, 0, 64, 0);
        PlayerData d = initialize(store, from);
        MovementListener listener = listener(store);
        for (int i = 0; i < 7; i++) {
            PlayerMoveEvent event =
                    new PlayerMoveEvent(player, from, new Location(world, 5, 64, 0));
            listener.onMoveLowest(event);
            listener.handleMove(event, 20000L + i * 10, 20000000000L + i * 10000000L);
            assertEquals(0.0, event.getTo().getX());
        }
        assertTrue(d.timerBalance > 0.0);
        assertEquals(20060000000L, d.lastMoveNanos);
    }

    @Test
    void malformedExternalTeleportCannotGrantGraceOrPoisonPosition() {
        PlayerDataStore store = new PlayerDataStore();
        Location from = new Location(world, 0, 64, 0);
        PlayerData d = initialize(store, from);
        MovementListener listener = listener(store);
        PlayerTeleportEvent event =
                new PlayerTeleportEvent(
                        player,
                        from,
                        new Location(world, Double.NaN, 128, 0),
                        PlayerTeleportEvent.TeleportCause.PLUGIN);
        listener.onTeleport(event);
        assertTrue(event.isCancelled());
        assertEquals(0, d.lastTeleport);
        assertEquals(0, d.teleportsApplied);
        assertEquals(0.0, d.lastSafe.getX());
        assertEquals(64.0, d.groundY);
    }

    @Test
    void acceptedMoveUpdatesTheSafeSetbackAnchor() {
        PlayerDataStore store = new PlayerDataStore();
        Location from = new Location(world, 0, 64, 0);
        PlayerData d = initialize(store, from);
        MovementListener listener = listener(store);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, new Location(world, 0.2, 64, 0));
        listener.onMoveLowest(event);
        listener.handleMove(event, 20000L, 20000000000L);
        assertEquals(0.2, event.getTo().getX());
        assertEquals(0.2, d.lastSafe.getX());
        assertEquals(20000, d.lastSafeAt);
    }

    @Test
    void repeatedRejectedMovesProduceNoNetProgressOrAcceptedReachHistory() {
        PlayerDataStore store = new PlayerDataStore();
        Location from = new Location(world, 0, 64, 0);
        PlayerData d = initialize(store, from);
        MovementListener listener = listener(store);
        for (int i = 0; i < 30; i++) {
            PlayerMoveEvent e = new PlayerMoveEvent(player, from, new Location(world, 5, 64, 0));
            listener.onMoveLowest(e);
            listener.handleMove(e, 20_000 + i * 50, 20_000_000_000L + i * 50_000_000L);
            assertTrue(e.isCancelled());
            assertEquals(0, e.getTo().getX());
            assertEquals(0, d.syncX);
            assertEquals(0, d.lastSafe.getX());
        }
        assertTrue(d.history.moves().isEmpty());
    }
}
