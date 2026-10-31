package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.flag.FlagSink;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PunisherHardeningTest {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> {
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            return h.invoke(p,m,a);
        });
    }
    private final World world = proxy(World.class, (p,m,a) -> null);
    private final Location safe = new Location(world, 1, 64, 1);
    private final PlayerData data = new PlayerData(UUID.randomUUID(),"test");
    private final AtomicReference<Consumer<ScheduledTask>> completion = new AtomicReference<>();
    private final EntityScheduler scheduler = proxy(EntityScheduler.class, (p,m,a) -> {
        if (m.getName().equals("run")) {
            @SuppressWarnings("unchecked") Consumer<ScheduledTask> c = (Consumer<ScheduledTask>)a[1];
            completion.set(c);
        }
        return null;
    });
    private Player player(CompletableFuture<Boolean> teleport) {
        return proxy(Player.class, (p,m,a) -> switch(m.getName()) {
            case "getWorld" -> world;
            case "getLocation" -> safe.clone();
            case "getScheduler" -> scheduler;
            case "teleportAsync" -> teleport;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    private Punisher punisher() {
        FlagSink sink = new FlagSink() {
            public String flag(Player p,PlayerData d,String c,String r,String details) { return "flag"; }
            public void action(String id,String action) {}
        };
        return new Punisher(null,Logger.getAnonymousLogger(),sink,s -> {},CheckConfig.defaults(),() -> 10_000);
    }
    @Test void safetyFailureCannotSilentlyAuthorizeTeleport() {
        Punisher punish = punisher();
        punish.targetValidator = (p,t) -> { throw new IllegalStateException("unavailable geometry"); };
        assertFalse(punish.setbackTo(player(null),data,safe,"test"));
        assertEquals(0,data.setbacksRequested);
        assertEquals(1,data.setbacksFailed);
        assertNull(data.pendingSetback);
    }
    @Test void nullTeleportFutureCannotLeaveFakeInFlightSetback() {
        Punisher punish = punisher();
        punish.targetValidator = (p,t) -> null;
        assertFalse(punish.setbackTo(player(null),data,safe,"test"));
        assertEquals(1,data.setbacksFailed);
        assertNull(data.pendingSetback);
    }
    @Test void futureCompletionDoesNotMutatePlayerStateOffItsScheduler() {
        Punisher punish = punisher();
        punish.targetValidator = (p,t) -> null;
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        assertTrue(punish.setbackTo(player(future),data,safe,"test"));
        future.complete(false);
        assertEquals(0,data.setbacksFailed);
        assertNotNull(data.pendingSetback);
        assertNotNull(completion.get());
        completion.get().accept(null);
        assertEquals(1,data.setbacksFailed);
        assertNull(data.pendingSetback);
    }
    @Test void missingServerHealthCannotAuthorizeAutomaticKick() {
        assertFalse(Punisher.sufficientTps(-1,18));
        assertFalse(Punisher.sufficientTps(Double.NaN,18));
        assertFalse(Punisher.sufficientTps(Double.POSITIVE_INFINITY,18));
        assertFalse(Punisher.sufficientTps(17.9,18));
        assertTrue(Punisher.sufficientTps(18,18));
        assertTrue(Punisher.sufficientTps(-1,0));
        assertEquals(1000,Punisher.safePing(player(null)));
    }

    @Test void blockedLatestAnchorFallsBackToValidatedHistoryAndCoalescesRequests() {
        Punisher p = punisher();
        p.targetValidator = (player,t) -> t.getX()==2 ? "blocked" : null;
        data.lastSafe = new Location(world,2,64,1);
        data.safePositions.add(safe);
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        assertTrue(p.setback(player(f),data,null));
        assertEquals(1,data.pendingSetback.getX());
        assertEquals(1,data.setbacksRequested);
        assertTrue(p.setback(player(f),data,null));
        assertEquals(1,data.setbacksRequested);
        assertEquals(1,data.setbacksSkippedInFlight);
    }
}
