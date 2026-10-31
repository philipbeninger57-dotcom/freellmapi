package de.kyrosmp.anticheat.gui;

import de.kyrosmp.anticheat.core.ViolationLevels;
import de.kyrosmp.anticheat.flag.FlagService;
import de.kyrosmp.anticheat.flag.FlagStatus;
import de.kyrosmp.anticheat.storage.FlagStore;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

public final class GuiContext {
    public static final String PERM_GUI = "kyroac.sus";
    public static final String PERM_MANAGE = "kyroac.manage";
    public static final String PERM_TELEPORT = "kyroac.teleport";
    public static final String PERM_SPECTATE = "kyroac.spectate";
    public static final String PREFIX = "§8[§bKYRO§8] §7";
    private final Plugin plugin;
    private final FlagStore store;
    private final ViolationLevels levels;
    private final FlagService flags;
    private volatile GuiText text;
    private SusGui sus;
    private PlayerFlagsGui playerFlags;
    private FlagDetailGui flagDetail;
    private final InspectionManager inspections;
    private final Map<UUID, Long> loads = new ConcurrentHashMap<>();
    private final AtomicLong loadIds = new AtomicLong();
    private final Map<UUID, GuiContext.TeleportRequest> teleports = new ConcurrentHashMap<>();
    volatile Function<UUID, Player> players = Bukkit::getPlayer;
    private volatile ToLongFunction<UUID> teleportGeneration = id -> 0L;

    public GuiContext(
            Plugin plugin,
            FlagStore store,
            ViolationLevels levels,
            FlagService flags,
            GuiText text) {
        this.plugin = plugin;
        this.store = store;
        this.levels = levels;
        this.flags = flags;
        this.text = text;
        this.inspections = new InspectionManager(plugin);
    }

    public InspectionManager inspections() {
        return this.inspections;
    }

    public void setTeleportGeneration(ToLongFunction<UUID> f) {
        this.teleportGeneration = f;
    }

    void wire(SusGui sus, PlayerFlagsGui playerFlags, FlagDetailGui flagDetail) {
        this.sus = sus;
        this.playerFlags = playerFlags;
        this.flagDetail = flagDetail;
    }

    public static GuiContext create(
            Plugin plugin,
            FlagStore store,
            ViolationLevels levels,
            FlagService flags,
            GuiText text) {
        GuiContext ctx = new GuiContext(plugin, store, levels, flags, text);
        ctx.wire(new SusGui(ctx), new PlayerFlagsGui(ctx), new FlagDetailGui(ctx));
        return ctx;
    }

    public void setText(GuiText text) {
        this.text = text;
    }

    public Plugin plugin() {
        return this.plugin;
    }

    public FlagStore store() {
        return this.store;
    }

    public ViolationLevels levels() {
        return this.levels;
    }

    public FlagService flags() {
        return this.flags;
    }

    public GuiText text() {
        return this.text;
    }

    public SusGui sus() {
        return this.sus;
    }

    public PlayerFlagsGui playerFlags() {
        return this.playerFlags;
    }

    public FlagDetailGui flagDetail() {
        return this.flagDetail;
    }

    public long currentWindowMs() {
        return this.flags.settings().currentWindowMs();
    }

    public void onViewer(Player viewer, Runnable action) {
        if (this.plugin.isEnabled()) {
            viewer.getScheduler()
                    .run(
                            this.plugin,
                            task -> {
                                if (viewer.isOnline()) {
                                    action.run();
                                }
                            },
                            null);
        }
    }

    public void cancelLoads(UUID viewer) {
        this.loads.remove(viewer);
    }

    public <T> void load(Player viewer, CompletableFuture<T> future, Consumer<T> onViewerThread) {
        long request = this.loadIds.incrementAndGet();
        UUID id = viewer.getUniqueId();
        this.loads.put(id, request);
        future.whenComplete(
                (result, error) ->
                        this.onViewer(
                                viewer,
                                () -> {
                                    if (Long.valueOf(request).equals(this.loads.get(id))) {
                                        if (!viewer.hasPermission("kyroac.sus")) {
                                            this.cancelLoads(id);
                                            viewer.closeInventory();
                                        } else if (error != null) {
                                            viewer.closeInventory();
                                            viewer.sendMessage(
                                                    "§8[§bKYRO§8] §7§cCould not load data: §7"
                                                            + (this.store.isAvailable()
                                                                    ? rootMessage(error)
                                                                    : "database unavailable"));
                                        } else {
                                            onViewerThread.accept((T) result);
                                        }
                                    }
                                }));
    }

    public void teleportTo(Player viewer, Player target, boolean spectate) {
        this.beginTeleport(viewer, target, spectate, false);
    }

    private CompletableFuture<Boolean> beginTeleport(
            Player viewer, Player target, boolean spectate, boolean requireTeleport) {
        if (!this.canTeleport(viewer, spectate, requireTeleport)) {
            return CompletableFuture.completedFuture(false);
        } else {
            GuiContext.TeleportRequest request =
                    new GuiContext.TeleportRequest(spectate, requireTeleport, viewer.getGameMode());
            GuiContext.TeleportRequest old = this.teleports.put(viewer.getUniqueId(), request);
            if (old != null) {
                old.result.complete(false);
            }

            this.inspections.end(viewer.getUniqueId(), null);
            viewer.closeInventory();
            this.teleportTo(viewer, target, request, 1);
            return request.result;
        }
    }

    public void cancelTeleports(UUID viewer) {
        GuiContext.TeleportRequest request = this.teleports.remove(viewer);
        if (request != null) {
            request.result.complete(false);
        }
    }

    private boolean currentTeleport(Player viewer, GuiContext.TeleportRequest request) {
        return this.teleports.get(viewer.getUniqueId()) == request && !request.result.isDone();
    }

    private void teleportTo(
            Player viewer, Player target, GuiContext.TeleportRequest request, int retries) {
        if (this.currentTeleport(viewer, request) && this.plugin.isEnabled()) {
            ScheduledTask task =
                    target.getScheduler()
                            .run(
                                    this.plugin,
                                    t -> {
                                        if (this.currentTeleport(viewer, request)) {
                                            Location loc = target.getLocation().clone();
                                            long generation =
                                                    this.teleportGeneration.applyAsLong(
                                                            target.getUniqueId());
                                            String unsafe =
                                                    request.spectate
                                                            ? null
                                                            : unsafeDestination(loc);
                                            this.onViewer(
                                                    viewer,
                                                    () -> {
                                                        if (this.currentTeleport(viewer, request)) {
                                                            if (!this.canTeleport(
                                                                            viewer,
                                                                            request.spectate,
                                                                            request.requireTeleport)
                                                                    || viewer.getGameMode()
                                                                            != request.originalMode) {
                                                                request.result.complete(false);
                                                            } else if (!target.isOnline()) {
                                                                viewer.sendMessage(
                                                                        "§8[§bKYRO§8] §7§cThat"
                                                                            + " player is no longer"
                                                                            + " online.");
                                                                request.result.complete(false);
                                                            } else if (generation
                                                                    != this.teleportGeneration
                                                                            .applyAsLong(
                                                                                    target
                                                                                            .getUniqueId())) {
                                                                if (retries > 0) {
                                                                    this.teleportTo(
                                                                            viewer,
                                                                            target,
                                                                            request,
                                                                            retries - 1);
                                                                } else {
                                                                    viewer.sendMessage(
                                                                            "§8[§bKYRO§8] §7§cThe"
                                                                                + " player is"
                                                                                + " teleporting –"
                                                                                + " try again.");
                                                                    request.result.complete(false);
                                                                }
                                                            } else if (unsafe != null) {
                                                                viewer.sendMessage(
                                                                        "§8[§bKYRO§8] §7§cTeleport"
                                                                                + " refused: "
                                                                                + unsafe
                                                                                + " at the player's"
                                                                                + " position. Use"
                                                                                + " Spectate.");
                                                                request.result.complete(false);
                                                            } else {
                                                                if (request.spectate) {
                                                                    viewer.setGameMode(
                                                                            GameMode.SPECTATOR);
                                                                    if (viewer.getGameMode()
                                                                            != GameMode.SPECTATOR) {
                                                                        viewer.sendMessage(
                                                                                "§8[§bKYRO§8]"
                                                                                    + " §7§cCould"
                                                                                    + " not switch"
                                                                                    + " to spectator"
                                                                                    + " mode"
                                                                                    + " (blocked by"
                                                                                    + " another"
                                                                                    + " plugin) –"
                                                                                    + " spectating"
                                                                                    + " cancelled.");
                                                                        request.result.complete(
                                                                                false);
                                                                        return;
                                                                    }

                                                                    if (!this.currentTeleport(
                                                                                    viewer, request)
                                                                            || !this.canTeleport(
                                                                                    viewer,
                                                                                    true,
                                                                                    request.requireTeleport)) {
                                                                        request.result.complete(
                                                                                false);
                                                                        return;
                                                                    }

                                                                    InspectionManager.Session
                                                                            session =
                                                                                    this.inspections
                                                                                            .start(
                                                                                                    viewer,
                                                                                                    target,
                                                                                                    request.requireTeleport);
                                                                    if (session != null) {
                                                                        session.initialTeleport()
                                                                                .whenComplete(
                                                                                        (ok, err) ->
                                                                                                request
                                                                                                        .result
                                                                                                        .complete(
                                                                                                                Boolean
                                                                                                                                .TRUE
                                                                                                                                .equals(
                                                                                                                                        ok)
                                                                                                                        && err
                                                                                                                                == null));
                                                                        return;
                                                                    }
                                                                }

                                                                try {
                                                                    viewer.teleportAsync(loc)
                                                                            .whenComplete(
                                                                                    (ok, err) ->
                                                                                            this
                                                                                                    .onViewer(
                                                                                                            viewer,
                                                                                                            () -> {
                                                                                                                if (this
                                                                                                                        .currentTeleport(
                                                                                                                                viewer,
                                                                                                                                request)) {
                                                                                                                    boolean
                                                                                                                            success =
                                                                                                                                    Boolean
                                                                                                                                                    .TRUE
                                                                                                                                                    .equals(
                                                                                                                                                            ok)
                                                                                                                                            && err
                                                                                                                                                    == null;
                                                                                                                    viewer
                                                                                                                            .sendMessage(
                                                                                                                                    "§8[§bKYRO§8]"
                                                                                                                                        + " §7"
                                                                                                                                            + (success
                                                                                                                                                    ? (request.spectate
                                                                                                                                                            ? "Spectating:"
                                                                                                                                                                  + " "
                                                                                                                                                            : "Teleported"
                                                                                                                                                                  + " to ")
                                                                                                                                                    : "§cTeleport"
                                                                                                                                                          + " failed:"
                                                                                                                                                          + " ")
                                                                                                                                            + "§f"
                                                                                                                                            + target
                                                                                                                                                    .getName());
                                                                                                                    request
                                                                                                                            .result
                                                                                                                            .complete(
                                                                                                                                    success);
                                                                                                                }
                                                                                                            }));
                                                                } catch (RuntimeException ignored) {
                                                                    request.result.complete(false);
                                                                    viewer.sendMessage(
                                                                            "§8[§bKYRO§8]"
                                                                                + " §7§cTeleport"
                                                                                + " failed.");
                                                                }
                                                            }
                                                        }
                                                    });
                                        }
                                    },
                                    () -> this.teleportUnavailable(viewer, request));
            if (task == null) {
                this.teleportUnavailable(viewer, request);
            }
        } else {
            request.result.complete(false);
        }
    }

    private void teleportUnavailable(Player viewer, GuiContext.TeleportRequest request) {
        this.onViewer(
                viewer,
                () -> {
                    if (this.currentTeleport(viewer, request)) {
                        viewer.sendMessage("§8[§bKYRO§8] §7§cThat player is no longer online.");
                        request.result.complete(false);
                    }
                });
    }

    private boolean canTeleport(Player viewer, boolean spectate, boolean requireTeleport) {
        String permission = spectate ? "kyroac.spectate" : "kyroac.teleport";
        if (!viewer.hasPermission("kyroac.sus")
                || !viewer.hasPermission(permission)
                || requireTeleport && !viewer.hasPermission("kyroac.teleport")) {
            viewer.sendMessage("§8[§bKYRO§8] §7§cNo permission (" + permission + ").");
            return false;
        } else {
            return true;
        }
    }

    static String unsafeDestination(Location loc) {
        if (!InspectionManager.validTarget(loc)) {
            return "invalid destination";
        } else {
            try {
                Block feet = loc.getBlock();
                Block head = feet.getRelative(BlockFace.UP);

                for (Block b : new Block[] {feet, head}) {
                    if (b.getType() == Material.LAVA) {
                        return "lava";
                    }

                    if (b.getType() == Material.FIRE || b.getType() == Material.SOUL_FIRE) {
                        return "fire";
                    }

                    if (b.getType().isOccluding()) {
                        return "solid block";
                    }
                }

                return null;
            } catch (RuntimeException ignored) {
                return "unavailable destination";
            }
        }
    }

    public void investigate(Player viewer, UUID targetId, String targetName) {
        if (viewer.hasPermission("kyroac.teleport") && viewer.hasPermission("kyroac.spectate")) {
            Player target = this.players.apply(targetId);
            if (target == null) {
                viewer.closeInventory();
                viewer.sendMessage("§8[§bKYRO§8] §7§cThat player is no longer online.");
            } else {
                CompletableFuture<Boolean> result = this.beginTeleport(viewer, target, true, true);
                GuiContext.TeleportRequest request = this.teleports.get(viewer.getUniqueId());
                result.whenComplete(
                        (ok, error) ->
                                this.onViewer(
                                        viewer,
                                        () -> {
                                            if (Boolean.TRUE.equals(ok)
                                                    && error == null
                                                    && request != null
                                                    && this.teleports.get(viewer.getUniqueId())
                                                            == request
                                                    && viewer.hasPermission("kyroac.sus")
                                                    && viewer.hasPermission("kyroac.manage")
                                                    && viewer.hasPermission("kyroac.teleport")
                                                    && viewer.hasPermission("kyroac.spectate")) {
                                                this.store
                                                        .updatePlayerStatus(
                                                                targetId,
                                                                List.of(FlagStatus.NEW),
                                                                FlagStatus.REVIEWED,
                                                                viewer.getUniqueId(),
                                                                viewer.getName(),
                                                                "INVESTIGATE",
                                                                System.currentTimeMillis())
                                                        .whenComplete(
                                                                (n, err) -> {
                                                                    if (err == null && n > 0) {
                                                                        this.onViewer(
                                                                                viewer,
                                                                                () ->
                                                                                        viewer
                                                                                                .sendMessage(
                                                                                                        "§8[§bKYRO§8]"
                                                                                                            + " §7"
                                                                                                                + n
                                                                                                                + " new flag(s)"
                                                                                                                + " of §f"
                                                                                                                + targetName
                                                                                                                + " §7marked"
                                                                                                                + " reviewed"
                                                                                                                + " §8(removed"
                                                                                                                + " from"
                                                                                                                + " /sus"
                                                                                                                + " until"
                                                                                                                + " a new"
                                                                                                                + " flag)"));
                                                                    }
                                                                });
                                            }
                                        }));
            }
        } else {
            viewer.sendMessage(
                    "§8[§bKYRO§8] §7§cNo permission (kyroac.teleport + kyroac.spectate).");
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;

        while (c.getCause() != null) {
            c = c.getCause();
        }

        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    private static final class TeleportRequest {
        final boolean spectate;
        final boolean requireTeleport;
        final GameMode originalMode;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();

        TeleportRequest(boolean spectate, boolean requireTeleport, GameMode originalMode) {
            this.spectate = spectate;
            this.requireTeleport = requireTeleport;
            this.originalMode = originalMode;
        }
    }
}
