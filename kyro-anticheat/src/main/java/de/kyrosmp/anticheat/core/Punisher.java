package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.flag.FlagSink;

import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.entity.TeleportFlag.EntityState;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

public final class Punisher implements SetbackService {
    public static final long SETBACK_IN_FLIGHT_MS = 1000L;
    private final Plugin plugin;
    private final Logger log;
    private final FlagSink sink;
    private final LongSupplier clock;
    private volatile PreventionConfig prevention = PreventionConfig.defaults();
    volatile BiFunction<Player, Location, String> targetValidator = Punisher::targetProblem;
    private volatile boolean groundFallback = true;

    public Punisher(
            Plugin plugin,
            Logger log,
            FlagSink sink,
            Consumer<String> staffAlert,
            CheckConfig cfg) {
        this(plugin, log, sink, staffAlert, cfg, System::currentTimeMillis);
    }

    public Punisher(
            Plugin plugin,
            Logger log,
            FlagSink sink,
            Consumer<String> staffAlert,
            CheckConfig cfg,
            LongSupplier clock) {
        this.plugin = plugin;
        this.log = log;
        this.sink = sink;
        this.clock = clock;
    }

    public void configure(CheckConfig ignored) {}

    public void configurePrevention(PreventionConfig config) {
        this.prevention = config;
    }

    @Override
    public boolean setback(Player p, PlayerData d, String flagId) {
        if (!this.prevention.movement()) {
            return false;
        } else {
            Location safe = d.lastSafe;
            if (safe != null && this.setbackTo(p, d, safe, flagId)) {
                return true;
            } else {
                if (this.prevention.safePositionHistory()) {
                    Iterator<Location> it = d.safePositions.descendingIterator();

                    while (it.hasNext()) {
                        Location older = it.next();
                        if (older != safe && this.setbackTo(p, d, older, flagId)) {
                            return true;
                        }
                    }
                }

                if (this.groundFallback) {
                    Location ground;
                    try {
                        ground = Collisions.groundBelow(p, p.getLocation(), 384);
                    } catch (RuntimeException ignored) {
                        ground = null;
                    }

                    return ground != null && this.setbackTo(p, d, ground, flagId);
                } else {
                    return false;
                }
            }
        }
    }

    public void configureStrict(StrictConfig strict) {
        this.groundFallback = strict.groundFallback();
    }

    @Override
    public boolean setbackTo(Player p, PlayerData d, Location safe, String flagId) {
        if (this.prevention.movement() && valid(safe) && safe.getWorld() == p.getWorld()) {
            long now = this.clock.getAsLong();
            if (d.pendingSetback != null
                    && now >= d.pendingSetbackAt
                    && now - d.pendingSetbackAt < 1000L) {
                d.setbacksSkippedInFlight++;
                return true;
            } else {
                String problem;
                try {
                    problem = this.targetValidator.apply(p, safe);
                } catch (RuntimeException ignored) {
                    problem = "target safety unavailable";
                }

                if (problem != null) {
                    d.setbacksFailed++;
                    return false;
                } else {
                    Location target = safe.clone();
                    Location current = p.getLocation();
                    target.setYaw(Float.isFinite(current.getYaw()) ? current.getYaw() : 0.0F);
                    target.setPitch(Float.isFinite(current.getPitch()) ? current.getPitch() : 0.0F);
                    long seq = ++d.setbackSeq;
                    d.pendingSetback = target;
                    d.pendingSetbackAt = now;
                    d.setbacksRequested++;

                    CompletableFuture<Boolean> future;
                    try {
                        future = p.teleportAsync(target, TeleportCause.PLUGIN);
                    } catch (RuntimeException ignored) {
                        future = null;
                    }

                    if (future == null) {
                        d.pendingSetback = null;
                        d.setbacksFailed++;
                        return false;
                    } else {
                        future.whenComplete(
                                (ok, err) -> {
                                    try {
                                        p.getScheduler()
                                                .run(
                                                        this.plugin,
                                                        task ->
                                                                this.completeSetback(
                                                                        d,
                                                                        target,
                                                                        seq,
                                                                        Boolean.TRUE.equals(ok)
                                                                                && err == null,
                                                                        flagId,
                                                                        this.clock.getAsLong()),
                                                        null);
                                    } catch (IllegalStateException var10x) {
                                    }
                                });
                        return true;
                    }
                }
            }
        } else {
            return false;
        }
    }

    void completeSetback(
            PlayerData d, Location target, long seq, boolean success, String flagId, long now) {
        if (d.setbackSeq == seq) {
            if (success) {
                if (d.pendingSetback == target) {
                    d.applyTeleport(target, now, true);
                }

                d.setbacksCompleted++;
            } else {
                if (d.pendingSetback == target) {
                    d.pendingSetback = null;
                }

                d.setbacksFailed++;
                if (flagId != null) {
                    this.sink.action(flagId, "Setback failed");
                }
            }
        }
    }

    static boolean valid(Location t) {
        return t != null
                && t.getWorld() != null
                && Double.isFinite(t.getX())
                && Double.isFinite(t.getY())
                && Double.isFinite(t.getZ());
    }

    static String targetProblem(Player p, Location t) {
        if (!valid(t)) {
            return "invalid position";
        } else {
            BoundingBox box = Collisions.playerBox(p, t);
            String problem = geometryProblem(t, box);
            if (problem != null) {
                return problem;
            } else {
                return !p.isGliding() && !Collisions.isGrounded(t.getWorld(), box)
                        ? "unsupported target"
                        : null;
            }
        }
    }

    static String geometryProblem(Location t, BoundingBox box) {
        World w = t.getWorld();
        if (!Collisions.queryable(w, box)) {
            return "target chunk unloaded or not owned";
        } else if (t.getY() < (double) (w.getMinHeight() + 1)
                || box.getMaxY() >= (double) w.getMaxHeight()) {
            return "world height";
        } else if (!w.getWorldBorder().isInside(t)) {
            return "world border";
        } else {
            Material below =
                    w.getBlockAt(t.getBlockX(), t.getBlockY() - 1, t.getBlockZ()).getType();
            if (below != Material.LAVA
                    && below != Material.MAGMA_BLOCK
                    && below != Material.CAMPFIRE
                    && below != Material.SOUL_CAMPFIRE) {
                for (int x = (int) Math.floor(box.getMinX());
                        x <= (int) Math.floor(box.getMaxX());
                        x++) {
                    for (int y = (int) Math.floor(box.getMinY());
                            y <= (int) Math.floor(box.getMaxY());
                            y++) {
                        for (int z = (int) Math.floor(box.getMinZ());
                                z <= (int) Math.floor(box.getMaxZ());
                                z++) {
                            Block b = w.getBlockAt(x, y, z);
                            Material type = b.getType();
                            if (type == Material.LAVA
                                    || type == Material.FIRE
                                    || type == Material.SOUL_FIRE
                                    || type == Material.MAGMA_BLOCK
                                    || type == Material.CAMPFIRE
                                    || type == Material.SOUL_CAMPFIRE) {
                                return "hazardous target";
                            }

                            if (!b.isPassable() && b.getBoundingBox().overlaps(box)) {
                                return "blocked target";
                            }
                        }
                    }
                }

                return null;
            } else {
                return "hazard below target";
            }
        }
    }

    public boolean setbackBoat(Boat boat, Location to) {
        if (this.prevention.movement() && valid(to) && to.getWorld() == boat.getWorld()) {
            Location target = to.clone();

            try {
                if (!Bukkit.isOwnedByCurrentRegion(boat)) {
                    return false;
                } else {
                    BoundingBox box = boat.getBoundingBox().clone();
                    Location at = boat.getLocation();
                    box.shift(
                            target.getX() - at.getX(),
                            target.getY() - at.getY(),
                            target.getZ() - at.getZ());
                    if (geometryProblem(target, box) != null) {
                        return false;
                    } else {
                        target.setYaw(at.getYaw());
                        target.setPitch(at.getPitch());
                        boat.setVelocity(new Vector());
                        boat.teleportAsync(
                                target,
                                TeleportCause.PLUGIN,
                                new TeleportFlag[] {EntityState.RETAIN_PASSENGERS});
                        return true;
                    }
                }
            } catch (RuntimeException ignored) {
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public void registerFlyPunishment(Player p, PlayerData d, long now, String id) {}

    public void registerBoatFlyPunishment(Player p, PlayerData d, long now, String id) {}

    static boolean sufficientTps(double tps, double minimum) {
        return minimum == 0.0 || Double.isFinite(tps) && tps >= minimum;
    }

    static boolean register(Deque<Long> window, long now, long windowMs, int limit) {
        window.addLast(now);

        while (!window.isEmpty() && now - window.peekFirst() > windowMs) {
            window.removeFirst();
        }

        return window.size() >= limit;
    }

    public void runForPlayer(Player p, Runnable task) {
        if (p != null && task != null && this.plugin != null && this.plugin.isEnabled()) {
            try {
                p.getScheduler().run(this.plugin, t -> task.run(), null);
            } catch (IllegalStateException ignored) {
                this.log.fine("Player task unavailable during shutdown");
            }
        }
    }

    public static int safePing(Player p) {
        try {
            int ping = p.getPing();
            return ping >= 0 ? ping : 1000;
        } catch (RuntimeException ignored) {
            return 1000;
        }
    }
}
