package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.flag.FlagSink;
import io.papermc.paper.entity.TeleportFlag;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Central validated setback engine. No automatic kick or ban implementation exists. */
public final class Punisher implements SetbackService {
    public static final long SETBACK_IN_FLIGHT_MS = 1000L;
    private final Plugin plugin;
    private final Logger log;
    private final FlagSink sink;
    private final LongSupplier clock;
    private volatile PreventionConfig prevention = PreventionConfig.defaults();
    volatile BiFunction<Player, Location, String> targetValidator = Punisher::targetProblem;
    public Punisher(Plugin plugin, Logger log, FlagSink sink, Consumer<String> staffAlert, CheckConfig cfg) {
        this(plugin, log, sink, staffAlert, cfg, System::currentTimeMillis);
    }
    public Punisher(Plugin plugin, Logger log, FlagSink sink, Consumer<String> staffAlert, CheckConfig cfg, LongSupplier clock) {
        this.plugin = plugin; this.log = log; this.sink = sink; this.clock = clock;
    }
    public void configure(CheckConfig ignored) { }
    public void configurePrevention(PreventionConfig config) { this.prevention = config; }
    @Override public boolean setback(Player p, PlayerData d, String flagId) {
        if (!prevention.movement()) return false;
        Location safe = d.lastSafe;
        if (safe != null && setbackTo(p, d, safe, flagId)) return true;
        if (prevention.safePositionHistory()) {
            for (var it = d.safePositions.descendingIterator(); it.hasNext();) {
                Location older = it.next();
                if (older != safe && setbackTo(p, d, older, flagId)) return true;
            }
        }
        // 3.7: without a recorded anchor (for example right after a teleport) drop the player onto
        // the first solid ground straight below instead of leaving them in the air.
        if (groundFallback) {
            Location ground;
            try {
                ground = Collisions.groundBelow(p, p.getLocation(), 384);
            } catch (RuntimeException ex) {
                ground = null;
            }
            return ground != null && setbackTo(p, d, ground, flagId);
        }
        return false;
    }

    private volatile boolean groundFallback = true;

    public void configureStrict(StrictConfig strict) {
        this.groundFallback = strict.groundFallback();
    }
    @Override public boolean setbackTo(Player p, PlayerData d, Location safe, String flagId) {
        if (!prevention.movement() || !valid(safe) || safe.getWorld() != p.getWorld()) return false;
        long now = clock.getAsLong();
        if (d.pendingSetback != null && now >= d.pendingSetbackAt && now - d.pendingSetbackAt < SETBACK_IN_FLIGHT_MS) {
            ++d.setbacksSkippedInFlight; return true;
        }
        String problem;
        try { problem = targetValidator.apply(p, safe); }
        catch (RuntimeException ex) { problem = "target safety unavailable"; }
        if (problem != null) { ++d.setbacksFailed; return false; }
        Location target = safe.clone();
        Location current = p.getLocation();
        target.setYaw(Float.isFinite(current.getYaw()) ? current.getYaw() : 0);
        target.setPitch(Float.isFinite(current.getPitch()) ? current.getPitch() : 0);
        long seq = ++d.setbackSeq;
        d.pendingSetback = target; d.pendingSetbackAt = now; ++d.setbacksRequested;
        CompletableFuture<Boolean> future;
        try { future = p.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN); }
        catch (RuntimeException ex) { future = null; }
        if (future == null) { d.pendingSetback = null; ++d.setbacksFailed; return false; }
        future.whenComplete((ok, err) -> {
            try {
                p.getScheduler().run(plugin, task -> completeSetback(d, target, seq,
                        Boolean.TRUE.equals(ok) && err == null, flagId, clock.getAsLong()), null);
            } catch (IllegalStateException ignored) { }
        });
        return true;
    }
    void completeSetback(PlayerData d, Location target, long seq, boolean success, String flagId, long now) {
        if (d.setbackSeq != seq) return;
        if (success) {
            if (d.pendingSetback == target) d.applyTeleport(target, now, true);
            ++d.setbacksCompleted;
        } else {
            if (d.pendingSetback == target) d.pendingSetback = null;
            ++d.setbacksFailed;
            if (flagId != null) sink.action(flagId, "Setback failed");
        }
    }
    static boolean valid(Location t) {
        return t != null && t.getWorld() != null && Double.isFinite(t.getX())
                && Double.isFinite(t.getY()) && Double.isFinite(t.getZ());
    }
    static String targetProblem(Player p, Location t) {
        if (!valid(t)) return "invalid position";
        BoundingBox box = Collisions.playerBox(p, t);
        String problem = geometryProblem(t, box);
        if (problem != null) return problem;
        if (!p.isGliding() && !Collisions.isGrounded(t.getWorld(), box)) return "unsupported target";
        return null;
    }
    static String geometryProblem(Location t, BoundingBox box) {
        World w = t.getWorld();
        if (!Collisions.queryable(w, box)) return "target chunk unloaded or not owned";
        if (t.getY() < w.getMinHeight() + 1 || box.getMaxY() >= w.getMaxHeight()) return "world height";
        if (!w.getWorldBorder().isInside(t)) return "world border";
        Material below = w.getBlockAt(t.getBlockX(), t.getBlockY()-1, t.getBlockZ()).getType();
        if (below == Material.LAVA || below == Material.MAGMA_BLOCK || below == Material.CAMPFIRE
                || below == Material.SOUL_CAMPFIRE) return "hazard below target";
        for (int x = (int)Math.floor(box.getMinX()); x <= (int)Math.floor(box.getMaxX()); x++) {
            for (int y = (int)Math.floor(box.getMinY()); y <= (int)Math.floor(box.getMaxY()); y++) {
                for (int z = (int)Math.floor(box.getMinZ()); z <= (int)Math.floor(box.getMaxZ()); z++) {
                    Block b = w.getBlockAt(x,y,z);
                    Material type = b.getType();
                    if (type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE
                            || type == Material.MAGMA_BLOCK || type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE)
                        return "hazardous target";
                    if (!b.isPassable() && b.getBoundingBox().overlaps(box)) return "blocked target";
                }
            }
        }
        return null;
    }
    public boolean setbackBoat(Boat boat, Location to) {
        if (!prevention.movement() || !valid(to) || to.getWorld() != boat.getWorld()) return false;
        Location target = to.clone();
        try {
            if (!Bukkit.isOwnedByCurrentRegion(boat)) return false;
            BoundingBox box = boat.getBoundingBox().clone();
            Location at = boat.getLocation();
            box.shift(target.getX()-at.getX(), target.getY()-at.getY(), target.getZ()-at.getZ());
            if (geometryProblem(target, box) != null) return false;
            target.setYaw(at.getYaw()); target.setPitch(at.getPitch());
            boat.setVelocity(new Vector());
            boat.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS);
            return true;
        } catch (RuntimeException ex) { return false; }
    }
    /** Compatibility entry points; automatic punishments were removed in 3.6. */
    @Override public void registerFlyPunishment(Player p, PlayerData d, long now, String id) { }
    public void registerBoatFlyPunishment(Player p, PlayerData d, long now, String id) { }
    static boolean sufficientTps(double tps, double minimum) {
        return minimum == 0.0 || (Double.isFinite(tps) && tps >= minimum);
    }
    static boolean register(Deque<Long> window, long now, long windowMs, int limit) {
        window.addLast(now);
        while (!window.isEmpty() && now - window.peekFirst() > windowMs) window.removeFirst();
        return window.size() >= limit;
    }
    public void runForPlayer(Player p, Runnable task) {
        if (p == null || task == null || plugin == null || !plugin.isEnabled()) return;
        try { p.getScheduler().run(plugin, t -> task.run(), null); }
        catch (IllegalStateException ex) { log.fine("Player task unavailable during shutdown"); }
    }
    public static int safePing(Player p) {
        try { int ping = p.getPing(); return ping >= 0 ? ping : 1000; }
        catch (RuntimeException ex) { return 1000; }
    }
}
