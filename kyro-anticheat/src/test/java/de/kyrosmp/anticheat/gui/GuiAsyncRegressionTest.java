package de.kyrosmp.anticheat.gui;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiAsyncRegressionTest {
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(p);
            if (method.getName().equals("equals")) return p == args[0];
            if (method.getName().equals("toString")) return type.getSimpleName() + "TestProxy";
            Object result = handler.invoke(p, method, args);
            if (result != null) return result;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == double.class) return 0.0;
            if (method.getReturnType() == float.class) return 0.0f;
            return null;
        });
    }

    static class Entity {
        final UUID id = UUID.randomUUID();
        final List<Runnable> queued = new ArrayList<>();
        final List<Runnable> retired = new ArrayList<>();
        boolean permission = true;
        final Set<String> denied = new HashSet<>();
        GameMode mode = GameMode.SPECTATOR;
        Runnable modeChanged = () -> {};
        final Block block = proxy(Block.class, (p,m,a) -> switch(m.getName()) {
            case "getType" -> Material.AIR;
            case "getRelative" -> p;
            default -> null;
        });
        final World world = proxy(World.class, (p,m,a) -> m.getName().equals("getBlockAt") ? block : null);
        Location location = new Location(world, 1, 64, 1);
        CompletableFuture<Boolean> teleportResult = CompletableFuture.completedFuture(true);
        final List<Location> destinations = new ArrayList<>();
        int closes;
        final ScheduledTask task = proxy(ScheduledTask.class, (p,m,a) -> null);
        final EntityScheduler scheduler = proxy(EntityScheduler.class, (p,m,a) -> {
            if (m.getName().equals("run") || m.getName().equals("runAtFixedRate")) {
                @SuppressWarnings("unchecked") Consumer<ScheduledTask> action = (Consumer<ScheduledTask>) a[1];
                queued.add(() -> action.accept(task));
                if (a[2] != null) retired.add((Runnable)a[2]);
                return task;
            }
            return null;
        });
        final Player player = proxy(Player.class, (p,m,a) -> switch(m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "Tester";
            case "isOnline" -> true;
            case "hasPermission" -> permission && !denied.contains((String)a[0]);
            case "getScheduler" -> scheduler;
            case "getGameMode" -> mode;
            case "setGameMode" -> { mode = (GameMode)a[0]; modeChanged.run(); yield null; }
            case "getLocation" -> location.clone();
            case "getWorld" -> world;
            case "teleportAsync" -> { destinations.add(((Location)a[0]).clone()); yield teleportResult; }
            case "closeInventory" -> { closes++; yield null; }
            default -> null;
        });
        void runQueued() {
            List<Runnable> copy = List.copyOf(queued);
            queued.clear();
            copy.forEach(Runnable::run);
        }
    }
    final Plugin plugin = proxy(Plugin.class, (p,m,a) -> m.getName().equals("isEnabled") ? true : null);
    GuiContext context() { return new GuiContext(plugin, null, null, null, new GuiText("dd.MM.yyyy", "UTC")); }

    @Test void oldResponseCannotOverwriteNewNavigation() {
        GuiContext ctx = context();
        Entity viewer = new Entity();
        List<String> rendered = new ArrayList<>();
        CompletableFuture<String> old = new CompletableFuture<>();
        CompletableFuture<String> latest = new CompletableFuture<>();
        ctx.load(viewer.player, old, rendered::add);
        ctx.load(viewer.player, latest, rendered::add);
        latest.complete("new page");
        old.complete("old page");
        viewer.runQueued();
        assertEquals(List.of("new page"), rendered);
    }

    @Test void closingMenuInvalidatesQueuedResponse() {
        GuiContext ctx = context();
        Entity viewer = new Entity();
        List<String> rendered = new ArrayList<>();
        CompletableFuture<String> pending = new CompletableFuture<>();
        ctx.load(viewer.player, pending, rendered::add);
        pending.complete("unwanted page");
        ctx.cancelLoads(viewer.id);
        viewer.runQueued();
        assertTrue(rendered.isEmpty());
        assertEquals(0, viewer.closes);
    }

    @Test void failedOldRequestCannotCloseNewPage() {
        GuiContext ctx = context();
        Entity viewer = new Entity();
        List<String> rendered = new ArrayList<>();
        CompletableFuture<String> old = new CompletableFuture<>();
        CompletableFuture<String> latest = new CompletableFuture<>();
        ctx.load(viewer.player, old, rendered::add);
        ctx.load(viewer.player, latest, rendered::add);
        latest.complete("new page");
        old.completeExceptionally(new IllegalStateException("old query failed"));
        viewer.runQueued();
        assertEquals(List.of("new page"), rendered);
        assertEquals(0, viewer.closes);
    }

    @Test void revokedPermissionPreventsOpeningPendingData() {
        GuiContext ctx = context();
        Entity viewer = new Entity();
        List<String> rendered = new ArrayList<>();
        CompletableFuture<String> pending = new CompletableFuture<>();
        ctx.load(viewer.player, pending, rendered::add);
        viewer.permission = false;
        pending.complete("private data");
        viewer.runQueued();
        assertTrue(rendered.isEmpty());
        assertEquals(1, viewer.closes);
    }

    @Test void retiredCallbackFromOldInspectionCannotEndReplacement() {
        InspectionManager manager = new InspectionManager(plugin);
        Entity staff = new Entity();
        Entity oldTarget = new Entity();
        Entity newTarget = new Entity();
        manager.players = id -> id.equals(oldTarget.id) ? oldTarget.player : id.equals(newTarget.id) ? newTarget.player : staff.player;
        InspectionManager.Session old = manager.start(staff.player, oldTarget.player);
        InspectionManager.Session replacement = manager.start(staff.player, newTarget.player);
        assertNotSame(old, replacement);
        oldTarget.retired.getFirst().run();
        assertSame(replacement, manager.current(staff.id));
        manager.shutdown();
    }
    @Test void closingOrdinaryInventoryCancelsInitialSusRequest() {
        GuiContext ctx = context();
        Entity viewer = new Entity();
        List<String> rendered = new ArrayList<>();
        CompletableFuture<String> pending = new CompletableFuture<>();
        ctx.load(viewer.player, pending, rendered::add);
        Inventory ordinary = proxy(Inventory.class, (p,m,args) -> null);
        InventoryView view = proxy(InventoryView.class, (p,m,args) -> switch(m.getName()) {
            case "getPlayer" -> viewer.player;
            case "getTopInventory" -> ordinary;
            default -> null;
        });
        new GuiListener(ctx).onClose(new InventoryCloseEvent(view));
        pending.complete("unwanted page");
        viewer.runQueued();
        assertTrue(rendered.isEmpty());
    }

    @Test void newerTeleportSupersedesAnOlderTargetSnapshot() {
        GuiContext ctx = context();
        ctx.inspections().configure(false, 100, 10);
        Entity viewer = new Entity();
        Entity oldTarget = new Entity();
        Entity newTarget = new Entity();
        newTarget.location.setX(20);
        ctx.teleportTo(viewer.player, oldTarget.player, true);
        ctx.teleportTo(viewer.player, newTarget.player, true);
        newTarget.runQueued();
        oldTarget.runQueued();
        viewer.runQueued();
        assertEquals(1, viewer.destinations.size());
        assertEquals(20, viewer.destinations.getFirst().getX());
    }

    @Test void revokedPermissionAfterSnapshotPreventsTeleport() {
        GuiContext ctx = context();
        ctx.inspections().configure(false, 100, 10);
        Entity viewer = new Entity();
        Entity target = new Entity();
        ctx.teleportTo(viewer.player, target.player, true);
        target.runQueued();
        viewer.denied.add(GuiContext.PERM_SPECTATE);
        viewer.runQueued();
        assertTrue(viewer.destinations.isEmpty());
    }

    @Test void synchronousGameModeListenerCannotRevokeAndStillTeleport() {
        GuiContext ctx = context();
        ctx.inspections().configure(false, 100, 10);
        Entity viewer = new Entity();
        Entity target = new Entity();
        viewer.mode = GameMode.SURVIVAL;
        viewer.modeChanged = () -> viewer.denied.add(GuiContext.PERM_SPECTATE);
        ctx.teleportTo(viewer.player, target.player, true);
        target.runQueued();
        viewer.runQueued();
        assertTrue(viewer.destinations.isEmpty());
    }

    @Test void leavingSpectatorCancelsPendingTeleport() {
        GuiContext ctx = context();
        ctx.inspections().configure(false, 100, 10);
        Entity viewer = new Entity();
        Entity target = new Entity();
        ctx.teleportTo(viewer.player, target.player, true);
        target.runQueued();
        new GuiListener(ctx).onGameMode(new PlayerGameModeChangeEvent(viewer.player, GameMode.SURVIVAL));
        viewer.runQueued();
        assertTrue(viewer.destinations.isEmpty());
    }

    @Test void deliberateTeleportStopsFollowingPreviousSuspect() {
        GuiContext ctx = context();
        Entity viewer = new Entity();
        Entity previous = new Entity();
        Entity target = new Entity();
        ctx.inspections().players = id -> id.equals(previous.id) ? previous.player : viewer.player;
        ctx.inspections().start(viewer.player, previous.player);
        assertNotNull(ctx.inspections().current(viewer.id));
        ctx.teleportTo(viewer.player, target.player, false);
        assertNull(ctx.inspections().current(viewer.id));
    }

    @Test void failedInvestigationNeverMarksEvidenceReviewed() {
        GuiContext ctx = context(); // No store: any accidental status mutation fails this test.
        Entity viewer = new Entity();
        Entity target = new Entity();
        ctx.players = id -> target.player;
        ctx.inspections().players = id -> id.equals(target.id) ? target.player : viewer.player;
        viewer.teleportResult = CompletableFuture.completedFuture(false);
        assertDoesNotThrow(() -> ctx.investigate(viewer.player, target.id, "Target"));
        target.runQueued();
        viewer.runQueued();
        InspectionManager.Session session = ctx.inspections().current(viewer.id);
        assertNotNull(session);
        ctx.inspections().correct(viewer.player, session, target.location, true);
        ctx.inspections().correct(viewer.player, session, target.location, true);
        ctx.inspections().correct(viewer.player, session, target.location, true);
        assertFalse(session.initialTeleport().join());
        assertDoesNotThrow(viewer::runQueued);
    }

    @Test void investigateRequiresTeleportPermissionAtInitialCorrection() {
        InspectionManager manager = new InspectionManager(plugin);
        Entity viewer = new Entity();
        Entity target = new Entity();
        manager.players = id -> target.player;
        InspectionManager.Session session = manager.start(viewer.player, target.player, true);
        viewer.denied.add(GuiContext.PERM_TELEPORT);
        manager.correct(viewer.player, session, target.location, true);
        assertTrue(viewer.destinations.isEmpty());
        manager.tick(viewer.player, session, viewer.task);
        assertFalse(session.initialTeleport().join());
    }

}
