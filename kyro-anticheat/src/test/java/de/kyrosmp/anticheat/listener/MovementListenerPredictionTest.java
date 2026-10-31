package de.kyrosmp.anticheat.listener;

import de.kyrosmp.anticheat.checks.combat.VelocityCheck;
import de.kyrosmp.anticheat.checks.movement.ElytraCheck;
import de.kyrosmp.anticheat.checks.movement.MovementChecks;
import de.kyrosmp.anticheat.checks.movement.PredictionChecks;
import de.kyrosmp.anticheat.core.*;
import de.kyrosmp.anticheat.debug.DebugManager;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The 3.7 prediction layer wired through the real PlayerMoveEvent handler. */
class MovementListenerPredictionTest {
    private static final UUID ID = UUID.randomUUID();
    private static final CheckConfig CONFIG = CheckConfig.defaults();
    private static final double FLOOR = 64.0;
    private int groundSetbacks;
    private final FlagSink sink = new FlagSink() {
        public String flag(Player p, PlayerData d, String check, String reason, String details) { return null; }
        public void action(String id, String action) {}
    };
    private final SetbackService setback = new SetbackService() {
        public boolean setback(Player p, PlayerData d, String id) { groundSetbacks++; return false; }
        public boolean setbackTo(Player p, PlayerData d, Location at, String id) { groundSetbacks++; return false; }
        public void registerFlyPunishment(Player p, PlayerData d, long now, String id) {}
    };

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, args) -> {
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
    private final Player player = proxy(Player.class, n -> switch (n) {
        case "getUniqueId" -> ID;
        case "getName" -> "Tester";
        case "getWorld" -> world;
        case "getNearbyEntities" -> java.util.List.of();
        default -> null;
    });

    private static MoveGeometry geometry(double fromY, double toY) {
        boolean ground = toY - FLOOR >= -1.0E-9 && toY - FLOOR < 0.12;
        boolean strict = Math.abs(toY - FLOOR) < 1.0E-9;
        boolean fromGround = fromY - FLOOR >= -1.0E-9 && fromY - FLOOR < 0.12;
        return proxy(MoveGeometry.class, n -> switch (n) {
            case "ground" -> ground;
            case "groundStrict" -> strict;
            case "fromGrounded" -> fromGround;
            case "nearGround" -> toY - FLOOR < 0.6;
            default -> null;
        });
    }

    private final PlayerDataStore store = new PlayerDataStore();
    private long now = 20_000L;

    private MovementListener listener() {
        MovementChecks moves = new MovementChecks(sink, setback, CONFIG);
        MovementListener.Checks checks = new MovementListener.Checks(moves, null, new VelocityCheck(null, sink, CONFIG), null,
                new ElytraCheck(sink, setback, CONFIG), new PredictionChecks(sink, setback, CONFIG, StrictConfig.defaults()), null);
        return new MovementListener(store, new ExemptionManager(CONFIG), sink, new DebugManager(null, 2),
                () -> checks, () -> CONFIG, AttributeSet.NONE,
                (p, from, to, t, nano, a) -> MoveContext.builder().time(t, nano).world(world)
                        .from(from.getX(), from.getY(), from.getZ()).to(to.getX(), to.getY(), to.getZ())
                        .sprinting(true).movementSpeedAttr(0.13).gravityAttr(0.08)
                        .geometry(geometry(from.getY(), to.getY())).build(), () -> now);
    }

    private PlayerData start(Location at) {
        PlayerData d = store.getQuiet(player);
        d.bedrock = false;
        d.positionInitialized = true;
        d.lastGround = true;
        d.groundY = at.getY();
        d.lastSafe = at.clone();
        d.lastSafeAt = 10_000;
        d.resetPrediction(true);
        d.setSync(at, 19_950);
        return d;
    }

    /** @return true when the move was rejected */
    private boolean move(MovementListener l, Location[] pos, double dx, double dy) {
        now += 50;
        Location from = pos[0];
        Location to = new Location(world, from.getX() + dx, from.getY() + dy, from.getZ());
        PlayerMoveEvent e = new PlayerMoveEvent(player, from, to);
        l.onMoveLowest(e);
        l.handleMove(e, now, now * 1_000_000L);
        if (e.isCancelled()) {
            l.onMoveMonitor(e);
            return true;
        }
        pos[0] = to;
        return false;
    }

    @Test void vanillaJumpPassesAndHoverIsRejectedRightAfterTheApex() {
        MovementListener l = listener();
        Location[] pos = {new Location(world, 0, FLOOR, 0)};
        start(pos[0]);
        double vy = 0.42;
        for (int i = 0; i < 5; i++) {
            assertFalse(move(l, pos, 0.06, vy), "rising tick " + i);
            vy = (vy - 0.08) * 0.98;
        }
        boolean rejected = false;
        for (int i = 0; i < 3 && !rejected; i++) rejected = move(l, pos, 0.06, 0.0);
        assertTrue(rejected, "hover after the apex must be rejected");
    }

    @Test void continuedHoverReturnsThePlayerToGround() {
        MovementListener l = listener();
        Location[] pos = {new Location(world, 0, FLOOR, 0)};
        start(pos[0]);
        move(l, pos, 0.06, 0.42);
        for (int i = 0; i < 10; i++) move(l, pos, 0.06, 0.0);
        assertTrue(groundSetbacks > 0);
    }

    @Test void moveCancelledByAnotherPluginRestartsThePredictionAtRest() {
        MovementListener l = listener();
        PlayerData d = start(new Location(world, 0, 80, 0));
        d.predVy = -0.9;
        PlayerMoveEvent e = new PlayerMoveEvent(player, new Location(world, 0, 80, 0), new Location(world, 0, 79, 0));
        e.setCancelled(true);
        l.onMoveMonitor(e);
        assertEquals(0.0, d.predVy);
        assertEquals(0.0, d.predH);
    }

    @Test void anotherPluginCannotVetoTheOwnSetbackTeleport() {
        MovementListener l = listener();
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("prevention.protect-setbacks", true);
        l.withStrict(() -> StrictConfig.from(config));
        PlayerData d = start(new Location(world, 0, FLOOR, 0));
        Location target = new Location(world, 3, FLOOR, 3);
        d.pendingSetback = target;
        d.pendingSetbackAt = now;
        PlayerTeleportEvent own = new PlayerTeleportEvent(player, new Location(world, 0, 70, 0), target.clone(),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        own.setCancelled(true);
        l.protectSetback(own);
        assertFalse(own.isCancelled());
        PlayerTeleportEvent other = new PlayerTeleportEvent(player, new Location(world, 0, 70, 0), new Location(world, 50, 70, 50),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        other.setCancelled(true);
        l.protectSetback(other);
        assertTrue(other.isCancelled(), "foreign teleports keep their cancellation");
    }

    @Test void defaultPolicyRespectsOtherPluginsTeleportCancellation() {
        MovementListener l = listener();
        PlayerData d = start(new Location(world, 0, FLOOR, 0));
        Location target = new Location(world, 3, FLOOR, 3);
        d.pendingSetback = target;
        d.pendingSetbackAt = now;
        PlayerTeleportEvent own = new PlayerTeleportEvent(player, new Location(world, 0, 70, 0), target.clone(),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        own.setCancelled(true);
        l.protectSetback(own);
        assertTrue(own.isCancelled(), "default must respect region/combat/teleport rules");
    }
}
