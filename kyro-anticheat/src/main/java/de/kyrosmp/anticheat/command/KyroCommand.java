package de.kyrosmp.anticheat.command;

import de.kyrosmp.anticheat.KyroAntiCheat;
import de.kyrosmp.anticheat.core.CheckInfo;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerHistory;
import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.gui.GuiText;
import de.kyrosmp.anticheat.gui.PlayerFlagsGui;
import de.kyrosmp.anticheat.gui.SusGui;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public final class KyroCommand implements TabExecutor {
    private static final String P = "§8[§bKYRO§8] §7";
    private static final List<String> SUBS =
            List.of("info", "violations", "history", "reset", "reload", "checks", "debug", "purge");
    private final KyroAntiCheat plugin;

    public KyroCommand(KyroAntiCheat plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("kyroac.command")) {
            s.sendMessage("§8[§bKYRO§8] §7§cNo permission.");
            return true;
        } else if (a.length == 0) {
            this.help(s, label);
            return true;
        } else {
            String sub = a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "info":
                    this.withTarget(s, a, "kyroac.command", this::info);
                    break;
                case "violations":
                case "vl":
                    this.withTarget(s, a, "kyroac.command", this::violations);
                    break;
                case "history":
                    this.history(s, a);
                    break;
                case "reset":
                    this.withTarget(s, a, "kyroac.clear", this::reset);
                    break;
                case "reload":
                    this.reload(s);
                    break;
                case "checks":
                    this.checks(s);
                    break;
                case "debug":
                    this.debug(s, a);
                    break;
                case "purge":
                    this.purge(s, a);
                    break;
                default:
                    this.help(s, label);
            }

            return true;
        }
    }

    private void help(CommandSender s, String label) {
        s.sendMessage("§8[§bKYRO§8] §7§fCommands:");
        s.sendMessage("§8 • §b/" + label + " info <player> §7– VL, exemptions, recent flags");
        s.sendMessage("§8 • §b/" + label + " violations <player> §7– current VL per check");
        s.sendMessage("§8 • §b/" + label + " history <player> §7– flag history GUI");
        s.sendMessage(
                "§8 • §b/" + label + " reset <player> §7– reset current VL (history is kept)");
        s.sendMessage("§8 • §b/" + label + " debug <player> §7– toggle live measurements");
        s.sendMessage("§8 • §b/" + label + " checks §7– all checks with status");
        s.sendMessage("§8 • §b/" + label + " reload §7– reload configuration");
        s.sendMessage(
                "§8 • §b/"
                        + label
                        + " purge <player> confirm §7– delete history (separate permission)");
    }

    private void withTarget(
            CommandSender s,
            String[] a,
            String perm,
            BiConsumer<CommandSender, KyroCommand.Target> action) {
        if (perm != null && !s.hasPermission(perm)) {
            s.sendMessage("§8[§bKYRO§8] §7§cNo permission (" + perm + ").");
        } else if (a.length < 2) {
            s.sendMessage("§8[§bKYRO§8] §7§cPlease specify a player name.");
        } else {
            this.resolve(a[1])
                    .whenComplete(
                            (t, err) ->
                                    this.onSender(
                                            s,
                                            () -> {
                                                if (s.hasPermission("kyroac.command")
                                                        && (perm == null
                                                                || s.hasPermission(perm))) {
                                                    if (err == null && !t.isEmpty()) {
                                                        action.accept(
                                                                s, (KyroCommand.Target) t.get());
                                                    } else {
                                                        s.sendMessage(
                                                                "§8[§bKYRO§8] §7§cPlayer '"
                                                                        + a[1]
                                                                        + "' not found (neither"
                                                                        + " online nor in the flag"
                                                                        + " database).");
                                                    }
                                                } else {
                                                    s.sendMessage(
                                                            "§8[§bKYRO§8] §7§cNo permission.");
                                                }
                                            }));
        }
    }

    private void onSender(CommandSender sender, Runnable action) {
        if (sender instanceof Player viewer) {
            this.plugin.gui().onViewer(viewer, action);
        } else {
            action.run();
        }
    }

    private CompletableFuture<Optional<KyroCommand.Target>> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(
                    Optional.of(new KyroCommand.Target(online.getUniqueId(), online.getName())));
        } else {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
            if (cached != null && cached.getName() != null) {
                return CompletableFuture.completedFuture(
                        Optional.of(
                                new KyroCommand.Target(cached.getUniqueId(), cached.getName())));
            } else {
                return !this.plugin.store().isAvailable()
                        ? CompletableFuture.completedFuture(Optional.empty())
                        : this.plugin
                                .store()
                                .findByName(name)
                                .thenApply(
                                        o ->
                                                o.map(
                                                        e ->
                                                                new KyroCommand.Target(
                                                                        e.getKey(), e.getValue())));
            }
        }
    }

    private void info(CommandSender s, KyroCommand.Target t) {
        long now = System.currentTimeMillis();
        GuiText text = this.plugin.gui().text();
        Player online = Bukkit.getPlayer(t.uuid());
        s.sendMessage(
                "§8[§bKYRO§8] §7§fInfo: §b"
                        + t.name()
                        + " §8("
                        + (online != null ? "§aonline" : "§8offline")
                        + "§8)");
        this.sendVl(s, t, now);
        if (online != null) {
            online.getScheduler()
                    .run(
                            this.plugin,
                            task -> {
                                PlayerData d = this.plugin.data(online.getUniqueId());
                                String bypass = this.plugin.exemptions().bypassReason(online);
                                PlayerHistory.Summary h =
                                        d == null
                                                ? null
                                                : d.history.summary(
                                                        System.currentTimeMillis(), 2000L);
                                ArrayList<String> lines = new ArrayList<>();
                                lines.add(
                                        "§7Ping: §f"
                                                + online.getPing()
                                                + " ms §8| §7Gamemode: §f"
                                                + online.getGameMode());
                                lines.add(
                                        "§7Active exemption: §f"
                                                + (bypass != null
                                                        ? bypass
                                                        : (d != null && d.lastExemptReason != null
                                                                ? d.lastExemptReason
                                                                : "none")));
                                if (d != null) {
                                    lines.add(
                                            String.format(
                                                    Locale.ROOT,
                                                    "§7Buffers: §fSpeed %.2f §8| §fFly %.2f §8|"
                                                        + " §fTimer %+.0fms §8| §fReach %.2f §8|"
                                                        + " §fBoat %.2f §8| §fSpoof %.1f",
                                                    d.speedBuffer,
                                                    d.flyBuffer,
                                                    d.timerBalance,
                                                    d.reachBuffer,
                                                    d.boatFlyBuffer,
                                                    d.groundSpoofBuffer));
                                    lines.add(
                                            "§7Bedrock: §f"
                                                    + (d.bedrock == null
                                                            ? "unknown"
                                                            : (d.bedrock ? "yes (exempt)" : "no"))
                                                    + " §8| §7Legacy counters Fly/Boat: §f"
                                                    + d.flyFlags.size()
                                                    + "/"
                                                    + d.boatFlyFlags.size());
                                    lines.add(
                                            String.format(
                                                    Locale.ROOT,
                                                    "§7Position sync: §f%d relocations, %d"
                                                        + " corrections, %d plugin redirects §8|"
                                                        + " §7Setbacks: §f%d requested, %d"
                                                        + " completed, %d failed, %d skipped (in"
                                                        + " flight)",
                                                    d.relocations,
                                                    d.corrections,
                                                    d.pluginRedirects,
                                                    d.setbacksRequested,
                                                    d.setbacksCompleted,
                                                    d.setbacksFailed,
                                                    d.setbacksSkippedInFlight));
                                }

                                if (h != null) {
                                    lines.add(
                                            String.format(
                                                    Locale.ROOT,
                                                    "§7Last 2 s: §f%d moves, h_max %.3f, h_avg"
                                                        + " %.3f, dy %.3f/%.3f, %d attacks on %d"
                                                        + " targets",
                                                    h.samples(),
                                                    h.maxHorizontal(),
                                                    h.avgHorizontal(),
                                                    h.maxUp(),
                                                    h.maxDown(),
                                                    h.attacks(),
                                                    h.distinctTargets()));
                                }

                                this.onSender(
                                        s,
                                        () -> {
                                            if (s.hasPermission("kyroac.command")) {
                                                lines.forEach(s::sendMessage);
                                            }
                                        });
                            },
                            null);
        }

        if (!this.plugin.store().isAvailable()) {
            s.sendMessage("§8[§bKYRO§8] §7§cFlag database unavailable.");
        } else {
            this.plugin
                    .store()
                    .stats(t.uuid())
                    .thenCombine(
                            this.plugin.store().recentFlags(t.uuid(), 5),
                            (stats, recent) -> {
                                List<String> lines = new ArrayList<>();
                                lines.add(
                                        "§7Stored flags: §f"
                                                + stats.total()
                                                + " §8("
                                                + stats.triggers()
                                                + " triggers) §7| New: §c"
                                                + stats.newCount()
                                                + " §7| Reviewed: §e"
                                                + stats.reviewedCount()
                                                + " §7| Resolved: §a"
                                                + stats.resolvedCount());
                                if (recent.isEmpty()) {
                                    lines.add("§7Recent flags: §anone");
                                } else {
                                    lines.add("§7Recent flags:");

                                    for (FlagRecord f : recent) {
                                        lines.add(
                                                String.format(
                                                        Locale.ROOT,
                                                        "§8 • §f%s §8[%s§8] §7VL §c%s §8| %s §8| %s"
                                                                + " §8| §7%s",
                                                        text.time(f.createdAt()),
                                                        f.category().displayName(),
                                                        GuiText.vl(f.vl()),
                                                        f.confidence().colored(),
                                                        f.status().colored(),
                                                        f.checkName() + ": " + f.reason()));
                                    }
                                }

                                return lines;
                            })
                    .whenComplete(
                            (lines, error) ->
                                    this.onSender(
                                            s,
                                            () -> {
                                                if (s.hasPermission("kyroac.command")) {
                                                    if (error != null) {
                                                        s.sendMessage(
                                                                "§8[§bKYRO§8] §7§cCould not load"
                                                                        + " flags.");
                                                    } else {
                                                        lines.forEach(s::sendMessage);
                                                    }
                                                }
                                            }));
        }
    }

    private void violations(CommandSender s, KyroCommand.Target t) {
        s.sendMessage("§8[§bKYRO§8] §7§fCurrent VL: §b" + t.name());
        this.sendVl(s, t, System.currentTimeMillis());
    }

    private void sendVl(CommandSender s, KyroCommand.Target t, long now) {
        Map<String, Double> vls = this.plugin.levels().snapshot(t.uuid(), now);
        if (vls.isEmpty()) {
            s.sendMessage("§7Current VL: §anone");
        } else {
            s.sendMessage(
                    "§7Current VL §8(decay "
                            + this.plugin.getConfig().getDouble("violations.decay-per-minute", 1.0)
                            + "/min):");
            vls.forEach(
                    (check, vl) ->
                            s.sendMessage(
                                    "§8 • §f"
                                            + check
                                            + " §8["
                                            + this.plugin
                                                    .registry()
                                                    .get(check)
                                                    .category()
                                                    .displayName()
                                            + "§8] §c"
                                            + GuiText.vl(vl)));
        }
    }

    private void history(CommandSender s, String[] a) {
        if (!(s instanceof Player viewer)) {
            s.sendMessage("§8[§bKYRO§8] §7§cIn-game only. Use /kyro info <player>.");
        } else if (!viewer.hasPermission("kyroac.sus")) {
            s.sendMessage("§8[§bKYRO§8] §7§cNo permission (kyroac.sus).");
        } else {
            this.withTarget(
                    s,
                    a,
                    "kyroac.sus",
                    (sender, t) ->
                            this.plugin
                                    .gui()
                                    .onViewer(
                                            viewer,
                                            () ->
                                                    this.plugin
                                                            .gui()
                                                            .playerFlags()
                                                            .open(
                                                                    viewer,
                                                                    PlayerFlagsGui.State.initial(
                                                                            t.uuid(),
                                                                            t.name(),
                                                                            SusGui.State
                                                                                    .initial()))));
        }
    }

    private void reset(CommandSender s, KyroCommand.Target t) {
        this.plugin.flags().resetCurrent(t.uuid());
        Player online = Bukkit.getPlayer(t.uuid());
        if (online != null) {
            online.getScheduler()
                    .run(
                            this.plugin,
                            task -> {
                                PlayerData d = this.plugin.data(online.getUniqueId());
                                if (d != null) {
                                    d.resetBuffers();
                                }
                            },
                            null);
        }

        UUID uUID;
        if (s instanceof Player p) {
            uUID = p.getUniqueId();
        } else {
            uUID = null;
        }

        this.plugin.store().audit(uUID, s.getName(), "RESET_VL", t.uuid(), "name=" + t.name());
        this.plugin.getLogger().info(s.getName() + " reset the current VL of " + t.name() + ".");
        s.sendMessage(
                "§8[§bKYRO§8] §7§aCurrent VL and buffers of §f"
                        + t.name()
                        + " §ahave been reset. §7The flag history is kept.");
    }

    private void purge(CommandSender s, String[] a) {
        if (!(s instanceof ConsoleCommandSender) && !s.hasPermission("kyroac.purge")) {
            s.sendMessage("§8[§bKYRO§8] §7§cNo permission (kyroac.purge).");
        } else if (a.length >= 3 && a[2].equalsIgnoreCase("confirm")) {
            this.withTarget(
                    s,
                    a,
                    s instanceof ConsoleCommandSender ? null : "kyroac.purge",
                    (sender, t) -> {
                        UUID uUID;
                        if (s instanceof Player p) {
                            uUID = p.getUniqueId();
                        } else {
                            uUID = null;
                        }

                        this.plugin
                                .store()
                                .deletePlayer(
                                        t.uuid(), uUID, s.getName(), System.currentTimeMillis())
                                .whenComplete(
                                        (n, err) -> {
                                            if (err != null) {
                                                s.sendMessage(
                                                        "§8[§bKYRO§8] §7§cDeletion failed – nothing"
                                                                + " was changed.");
                                            } else {
                                                this.plugin
                                                        .flags()
                                                        .clearHistoryReferences(t.uuid());
                                                this.plugin
                                                        .getLogger()
                                                        .warning(
                                                                s.getName()
                                                                        + " purged "
                                                                        + n
                                                                        + " flag records of "
                                                                        + t.name()
                                                                        + ".");
                                                s.sendMessage(
                                                        "§8[§bKYRO§8] §7§a"
                                                                + n
                                                                + " flag records of §f"
                                                                + t.name()
                                                                + " §adeleted. §7(logged)");
                                            }
                                        });
                    });
        } else {
            s.sendMessage("§8[§bKYRO§8] §7§cThis permanently deletes the entire flag history.");
            s.sendMessage(
                    "§8[§bKYRO§8] §7§7Confirm with: §f/kyro purge "
                            + (a.length > 1 ? a[1] : "<player>")
                            + " confirm");
        }
    }

    private void reload(CommandSender s) {
        if (!s.hasPermission("kyroac.reload")) {
            s.sendMessage("§8[§bKYRO§8] §7§cNo permission (kyroac.reload).");
        } else {
            List<String> messages = this.plugin.reloadAll();
            UUID uUID;
            if (s instanceof Player p) {
                uUID = p.getUniqueId();
            } else {
                uUID = null;
            }

            this.plugin
                    .store()
                    .audit(uUID, s.getName(), "RELOAD", null, "warnings=" + messages.size());
            if (messages.isEmpty()) {
                s.sendMessage("§8[§bKYRO§8] §7§aConfiguration reloaded, no problems found.");
            } else {
                s.sendMessage(
                        "§8[§bKYRO§8] §7§eConfiguration reloaded with "
                                + messages.size()
                                + " warning(s):");
                messages.forEach(m -> s.sendMessage("§8 • §e" + m));
            }
        }
    }

    private void checks(CommandSender s) {
        s.sendMessage(
                "§8[§bKYRO§8] §7§fRegistered checks §8("
                        + this.plugin.registry().all().size()
                        + "):");

        for (CheckInfo c : this.plugin.registry().all()) {
            boolean enabled = this.plugin.getConfig().getBoolean(c.enabledPath(), true);
            s.sendMessage(
                    "§8 • "
                            + (enabled ? "§a● " : "§c○ ")
                            + "§f"
                            + c.name()
                            + " §8["
                            + c.category().displayName()
                            + "§8] §7"
                            + c.enabledPath()
                            + "="
                            + enabled);
            if (s.hasPermission("kyroac.debug")) {
                s.sendMessage("§8     Limits: §7" + c.limitations());
            }
        }

        s.sendMessage(
                "§8[§bKYRO§8] §7§7Database: "
                        + (this.plugin.store().isAvailable() ? "§aOK" : "§cunavailable")
                        + " §8| §7queue: §f"
                        + this.plugin.store().queueSize()
                        + " §8| §7dropped: §f"
                        + this.plugin.store().droppedWrites()
                        + " §8| §7errors: §f"
                        + this.plugin.store().failedOperations());
    }

    private void debug(CommandSender s, String[] a) {
        if (!(s instanceof Player viewer)) {
            s.sendMessage("§8[§bKYRO§8] §7§cDebug output goes to chat and is in-game only.");
        } else {
            this.withTarget(
                    s,
                    a,
                    "kyroac.debug",
                    (sender, t) -> {
                        boolean on = this.plugin.debug().toggle(viewer.getUniqueId(), t.uuid());
                        s.sendMessage(
                                "§8[§bKYRO§8] §7Debug for §f"
                                        + t.name()
                                        + " "
                                        + (on ? "§aenabled" : "§cdisabled")
                                        + (on
                                                ? " §8(movement values throttled, flags and"
                                                        + " exemption changes instantly)"
                                                : ""));
                    });
        }
    }

    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("kyroac.command")) {
            return List.of();
        } else if (a.length == 1) {
            return filter(SUBS, a[0]);
        } else if (a.length == 2
                && !a[0].equalsIgnoreCase("reload")
                && !a[0].equalsIgnoreCase("checks")) {
            ArrayList<String> names = new ArrayList<>();

            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }

            return filter(names, a[1]);
        } else {
            return a.length == 3 && a[0].equalsIgnoreCase("purge")
                    ? filter(List.of("confirm"), a[2])
                    : List.of();
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        ArrayList<String> out = new ArrayList<>();

        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }

        return out;
    }

    private static record Target(UUID uuid, String name) {}
}
