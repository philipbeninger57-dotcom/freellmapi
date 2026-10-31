/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.papermc.paper.threadedregions.scheduler.ScheduledTask
 *  org.bukkit.Bukkit
 *  org.bukkit.GameMode
 *  org.bukkit.Location
 *  org.bukkit.entity.Player
 *  org.bukkit.plugin.Plugin
 */
package de.kyrosmp.anticheat.gui;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class InspectionManager {
    private final Plugin plugin;
    private final Map<UUID, Session> byStaff = new ConcurrentHashMap<UUID, Session>();
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

    public Session start(Player staff, Player target) {
        return this.start(staff, target, false);
    }

    public Session start(Player staff, Player target, boolean requireTeleport) {
        if (!this.enabled || !staff.isOnline() || staff.getGameMode() != GameMode.SPECTATOR
                || !staff.hasPermission(GuiContext.PERM_GUI) || !staff.hasPermission(GuiContext.PERM_SPECTATE)
                || (requireTeleport && !staff.hasPermission(GuiContext.PERM_TELEPORT))) {
            return null;
        }
        Session s = new Session(staff.getUniqueId(), target.getUniqueId(), target.getName(), this.ids.incrementAndGet(), requireTeleport);
        Session old = this.byStaff.put(s.staff(), s);
        if (old != null) {
            old.initialTeleport.complete(false);
            if (old.task != null) old.task.cancel();
        }
        long every = this.intervalTicks;
        s.task = staff.getScheduler().runAtFixedRate(this.plugin, task -> this.tick(staff, s, task), () -> this.end(s, null), every, every);
        if (s.task == null) {
            this.end(s, null);
            return null;
        }
        staff.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77Inspecting \u00a7f" + target.getName() + "\u00a77 \u2013 you are kept within \u00a7f" + (int)this.maxDistance + "\u00a77 blocks. Leave spectator mode (e.g. /gms) to stop.");
        this.followNow(staff, s, true);
        return s;
    }

    public boolean isCurrent(Session s) {
        return s != null && this.byStaff.get(s.staff()) == s;
    }

    public Session current(UUID staff) {
        return this.byStaff.get(staff);
    }

    public void end(UUID staff, String reason) {
        Session s = this.byStaff.get(staff);
        if (s != null) {
            this.end(s, reason);
        }
    }

    private void end(Session s, String reason) {
        if (!this.byStaff.remove(s.staff(), s)) {
            return;
        }
        s.initialTeleport.complete(false);
        Player p;
        if (s.task != null) {
            s.task.cancel();
        }
        if (reason != null && this.plugin.isEnabled() && (p = this.players.apply(s.staff())) != null) {
            p.getScheduler().run(this.plugin, task -> p.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77Inspection of \u00a7f" + s.targetName() + "\u00a77 ended (" + reason + ")."), null);
        }
    }

    public void onQuit(UUID player) {
        this.end(player, null);
        for (Session s : this.byStaff.values()) {
            if (!s.target().equals(player)) continue;
            this.end(s, "the player went offline");
        }
    }

    public void shutdown() {
        for (Session s : this.byStaff.values()) {
            s.initialTeleport.complete(false);
            if (s.task == null) continue;
            s.task.cancel();
        }
        this.byStaff.clear();
    }

    void tick(Player staff, Session s, ScheduledTask task) {
        if (!this.isCurrent(s)) {
            task.cancel();
            return;
        }
        if (!staff.isOnline()) {
            this.end(s, null);
            return;
        }
        if (!this.hasPermission(staff, s)) {
            this.end(s, "permission removed");
            return;
        }
        if (staff.getGameMode() != GameMode.SPECTATOR) {
            this.end(s, "left spectator mode");
            return;
        }
        this.followNow(staff, s, !s.initialDone);
    }

    private void followNow(Player staff, Session s, boolean initial) {
        Player target = this.players.apply(s.target());
        if (target == null || !target.isOnline()) {
            this.end(s, "the player went offline");
            return;
        }
        if (s.teleportPending.get()) {
            return;
        }
        ScheduledTask t = target.getScheduler().run(this.plugin, tt -> {
            Location now = target.getLocation().clone();
            staff.getScheduler().run(this.plugin, st -> this.correct(staff, s, now, initial), null);
        }, () -> this.end(s, "the player went offline"));
        if (t == null) {
            this.end(s, "the player went offline");
        }
    }

    void correct(Player staff, Session s, Location targetNow, boolean initial) {
        CompletableFuture<Boolean> f;
        if (!this.isCurrent(s) || !staff.isOnline() || staff.getGameMode() != GameMode.SPECTATOR
                || !this.hasPermission(staff, s)) {
            return;
        }
        final boolean firstTeleport = initial && !s.initialDone;
        if (!firstTeleport && !InspectionManager.needsCorrection(staff.getLocation(), targetNow, this.maxDistance)) {
            return;
        }
        if (!InspectionManager.validTarget(targetNow)) {
            this.failed(s);
            return;
        }
        if (!s.teleportPending.compareAndSet(false, true)) {
            return;
        }
        try {
            f = staff.teleportAsync(targetNow);
        }
        catch (Throwable ex) {
            s.teleportPending.set(false);
            this.failed(s);
            return;
        }
        if (f == null) {
            s.teleportPending.set(false);
            this.failed(s);
            return;
        }
        f.whenComplete((ok, err) -> {
            s.teleportPending.set(false);
            if (!this.isCurrent(s)) {
                return;
            }
            if (Boolean.TRUE.equals(ok) && err == null) {
                s.failures.set(0);
                if (firstTeleport) {
                    s.initialDone = true;
                    s.initialTeleport.complete(true);
                }
                return;
            }
            this.failed(s);
        });
    }

    private boolean hasPermission(Player staff, Session s) {
        return staff.hasPermission(GuiContext.PERM_GUI) && staff.hasPermission(GuiContext.PERM_SPECTATE)
                && (!s.requireTeleport || staff.hasPermission(GuiContext.PERM_TELEPORT));
    }

    private void failed(Session s) {
        if (this.isCurrent(s) && s.failures.incrementAndGet() >= 3) {
            this.end(s, "the player could not be reached");
        }
    }

    static boolean validTarget(Location t) {
        return t != null && t.getWorld() != null && Double.isFinite(t.getX()) && Double.isFinite(t.getY()) && Double.isFinite(t.getZ());
    }

    public static boolean needsCorrection(Location staff, Location target, double maxDistance) {
        if (staff == null || !InspectionManager.validTarget(target)) {
            return false;
        }
        if (staff.getWorld() != target.getWorld()) {
            return true;
        }
        double d2 = staff.distanceSquared(target);
        return !(d2 <= maxDistance * maxDistance);
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

