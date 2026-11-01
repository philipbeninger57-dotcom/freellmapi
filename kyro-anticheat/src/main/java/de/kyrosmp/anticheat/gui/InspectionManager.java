package de.kyrosmp.anticheat.gui;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class InspectionManager {
    private final Plugin plugin;
    private final Map<UUID, InspectionManager.Session> byStaff = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    volatile Function<UUID, Player> players = Bukkit::getPlayer;
    private volatile boolean enabled = true;
    private volatile double maxDistance = 100.0;
    private volatile long intervalTicks = 10L;

    public InspectionManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void configure(boolean enabled, double maxDistance, long intervalTicks) {
        this.enabled = enabled;
        this.maxDistance = Double.isFinite(maxDistance) && maxDistance >= 0.0 ? maxDistance : 100.0;
        this.intervalTicks = Math.max(5L, intervalTicks);
        if (!enabled) {
            this.shutdown();
        }
    }

    public double maxDistance() {
        return this.maxDistance;
    }

    public InspectionManager.Session start(Player staff, Player target) {
        return this.start(staff, target, false);
    }

    public InspectionManager.Session start(Player staff, Player target, boolean requireTeleport) {
        if (this.enabled
                && staff.isOnline()
                && staff.getGameMode() == GameMode.SPECTATOR
                && staff.hasPermission("kyroac.sus")
                && staff.hasPermission("kyroac.spectate")
                && (!requireTeleport || staff.hasPermission("kyroac.teleport"))) {
            InspectionManager.Session s =
                    new InspectionManager.Session(
                            staff.getUniqueId(),
                            target.getUniqueId(),
                            target.getName(),
                            this.ids.incrementAndGet(),
                            requireTeleport);
            InspectionManager.Session old = this.byStaff.put(s.staff(), s);
            if (old != null) {
                old.initialTeleport.complete(false);
                if (old.task != null) {
                    old.task.cancel();
                }
            }

            long every = this.intervalTicks;
            s.task =
                    staff.getScheduler()
                            .runAtFixedRate(
                                    this.plugin,
                                    task -> this.tick(staff, s, task),
                                    () -> this.end(s, null),
                                    every,
                                    every);
            if (s.task == null) {
                this.end(s, null);
                return null;
            } else {
                staff.sendMessage(
                        "§8[§bKYRO§8] §7Inspecting §f"
                                + target.getName()
                                + "§7 – you are kept within §f"
                                + (int) this.maxDistance
                                + "§7 blocks. Leave spectator mode (e.g. /gms) to stop.");
                this.followNow(staff, s, true);
                return s;
            }
        } else {
            return null;
        }
    }

    public boolean isCurrent(InspectionManager.Session s) {
        return s != null && this.byStaff.get(s.staff()) == s;
    }

    public InspectionManager.Session current(UUID staff) {
        return this.byStaff.get(staff);
    }

    public void end(UUID staff, String reason) {
        InspectionManager.Session s = this.byStaff.get(staff);
        if (s != null) {
            this.end(s, reason);
        }
    }

    private void end(InspectionManager.Session s, String reason) {
        if (this.byStaff.remove(s.staff(), s)) {
            s.initialTeleport.complete(false);
            if (s.task != null) {
                s.task.cancel();
            }

            Player p;
            if (reason != null
                    && this.plugin.isEnabled()
                    && (p = this.players.apply(s.staff())) != null) {
                p.getScheduler()
                        .run(
                                this.plugin,
                                task ->
                                        p.sendMessage(
                                                "§8[§bKYRO§8] §7Inspection of §f"
                                                        + s.targetName()
                                                        + "§7 ended ("
                                                        + reason
                                                        + ")."),
                                null);
            }
        }
    }

    public void onQuit(UUID player) {
        this.end(player, null);

        for (InspectionManager.Session s : this.byStaff.values()) {
            if (s.target().equals(player)) {
                this.end(s, "the player went offline");
            }
        }
    }

    public void shutdown() {
        for (InspectionManager.Session s : this.byStaff.values()) {
            s.initialTeleport.complete(false);
            if (s.task != null) {
                s.task.cancel();
            }
        }

        this.byStaff.clear();
    }

    void tick(Player staff, InspectionManager.Session s, ScheduledTask task) {
        if (!this.isCurrent(s)) {
            task.cancel();
        } else if (!staff.isOnline()) {
            this.end(s, null);
        } else if (!this.hasPermission(staff, s)) {
            this.end(s, "permission removed");
        } else if (staff.getGameMode() != GameMode.SPECTATOR) {
            this.end(s, "left spectator mode");
        } else {
            this.followNow(staff, s, !s.initialDone);
        }
    }

    private void followNow(Player staff, InspectionManager.Session s, boolean initial) {
        Player target = this.players.apply(s.target());
        if (target == null || !target.isOnline()) {
            this.end(s, "the player went offline");
        } else if (!s.teleportPending.get()) {
            ScheduledTask t =
                    target.getScheduler()
                            .run(
                                    this.plugin,
                                    tt -> {
                                        Location now = target.getLocation().clone();
                                        staff.getScheduler()
                                                .run(
                                                        this.plugin,
                                                        st -> this.correct(staff, s, now, initial),
                                                        null);
                                    },
                                    () -> this.end(s, "the player went offline"));
            if (t == null) {
                this.end(s, "the player went offline");
            }
        }
    }

    void correct(Player staff, InspectionManager.Session s, Location targetNow, boolean initial) {
        if (this.isCurrent(s)
                && staff.isOnline()
                && staff.getGameMode() == GameMode.SPECTATOR
                && this.hasPermission(staff, s)) {
            boolean firstTeleport = initial && !s.initialDone;
            if (firstTeleport
                    || needsCorrection(staff.getLocation(), targetNow, this.maxDistance)) {
                if (!validTarget(targetNow)) {
                    this.failed(s);
                } else if (s.teleportPending.compareAndSet(false, true)) {
                    CompletableFuture<Boolean> f;
                    try {
                        f = staff.teleportAsync(targetNow);
                    } catch (Throwable ignored) {
                        s.teleportPending.set(false);
                        this.failed(s);
                        return;
                    }

                    if (f == null) {
                        s.teleportPending.set(false);
                        this.failed(s);
                    } else {
                        f.whenComplete(
                                (ok, err) -> {
                                    s.teleportPending.set(false);
                                    if (this.isCurrent(s)) {
                                        if (Boolean.TRUE.equals(ok) && err == null) {
                                            s.failures.set(0);
                                            if (firstTeleport) {
                                                s.initialDone = true;
                                                s.initialTeleport.complete(true);
                                            }
                                        } else {
                                            this.failed(s);
                                        }
                                    }
                                });
                    }
                }
            }
        }
    }

    private boolean hasPermission(Player staff, InspectionManager.Session s) {
        return staff.hasPermission("kyroac.sus")
                && staff.hasPermission("kyroac.spectate")
                && (!s.requireTeleport || staff.hasPermission("kyroac.teleport"));
    }

    private void failed(InspectionManager.Session s) {
        if (this.isCurrent(s) && s.failures.incrementAndGet() >= 3) {
            this.end(s, "the player could not be reached");
        }
    }

    static boolean validTarget(Location t) {
        return t != null
                && t.getWorld() != null
                && Double.isFinite(t.getX())
                && Double.isFinite(t.getY())
                && Double.isFinite(t.getZ());
    }

    public static boolean needsCorrection(Location staff, Location target, double maxDistance) {
        if (staff != null && validTarget(target)) {
            if (staff.getWorld() != target.getWorld()) {
                return true;
            } else {
                double d2 = staff.distanceSquared(target);
                return !(d2 <= maxDistance * maxDistance);
            }
        } else {
            return false;
        }
    }

    public static final class Session {
        private final UUID staff;
        private final UUID target;
        private final String targetName;
        private final long id;
        private final boolean requireTeleport;
        final AtomicBoolean teleportPending = new AtomicBoolean();
        final AtomicInteger failures = new AtomicInteger();
        volatile ScheduledTask task;
        volatile boolean initialDone;
        final CompletableFuture<Boolean> initialTeleport = new CompletableFuture<>();

        public CompletableFuture<Boolean> initialTeleport() {
            return this.initialTeleport;
        }

        Session(UUID staff, UUID target, String targetName, long id, boolean requireTeleport) {
            this.staff = staff;
            this.target = target;
            this.targetName = targetName;
            this.id = id;
            this.requireTeleport = requireTeleport;
        }

        public UUID staff() {
            return this.staff;
        }

        public UUID target() {
            return this.target;
        }

        public String targetName() {
            return this.targetName;
        }

        public long id() {
            return this.id;
        }
    }
}
