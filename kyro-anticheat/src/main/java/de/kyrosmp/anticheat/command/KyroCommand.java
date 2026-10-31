/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.OfflinePlayer
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandSender
 *  org.bukkit.command.ConsoleCommandSender
 *  org.bukkit.command.TabExecutor
 *  org.bukkit.entity.Player
 *  org.bukkit.plugin.Plugin
 */
package de.kyrosmp.anticheat.command;

import de.kyrosmp.anticheat.KyroAntiCheat;
import de.kyrosmp.anticheat.core.CheckInfo;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerHistory;
import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.gui.GuiText;
import de.kyrosmp.anticheat.gui.PlayerFlagsGui;
import de.kyrosmp.anticheat.gui.SusGui;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class KyroCommand
implements TabExecutor {
    private static final String P = "\u00a78[\u00a7bKYRO\u00a78] \u00a77";
    private static final List<String> SUBS = List.of("info", "violations", "history", "reset", "reload", "checks", "debug", "purge");
    private final KyroAntiCheat plugin;

    public KyroCommand(KyroAntiCheat plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("kyroac.command")) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cNo permission.");
            return true;
        }
        if (a.length == 0) {
            this.help(s, label);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "info": {
                this.withTarget(s, a, "kyroac.command", this::info);
                break;
            }
            case "violations": 
            case "vl": {
                this.withTarget(s, a, "kyroac.command", this::violations);
                break;
            }
            case "history": {
                this.history(s, a);
                break;
            }
            case "reset": {
                this.withTarget(s, a, "kyroac.clear", this::reset);
                break;
            }
            case "reload": {
                this.reload(s);
                break;
            }
            case "checks": {
                this.checks(s);
                break;
            }
            case "debug": {
                this.debug(s, a);
                break;
            }
            case "purge": {
                this.purge(s, a);
                break;
            }
            default: {
                this.help(s, label);
            }
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7fCommands:");
        s.sendMessage("\u00a78 \u2022 \u00a7b/" + label + " info <player> \u00a77\u2013 VL, exemptions, recent flags");
        s.sendMessage("\u00a78 \u2022 \u00a7b/" + label + " violations <player> \u00a77\u2013 current VL per check");
        s.sendMessage("\u00a78 \u2022 \u00a7b/" + label + " history <player> \u00a77\u2013 flag history GUI");
        s.sendMessage("\u00a78 \u2022 \u00a7b/" + label + " reset <player> \u00a77\u2013 reset current VL (history is kept)");
        s.sendMessage("\u00a78 \u2022 \u00a7b/" + label + " debug <player> \u00a77\u2013 toggle live measurements");
        s.sendMessage("\u00a78 \u2022 \u00a7b/" + label + " checks \u00a77\u2013 all checks with status");
        s.sendMessage("\u00a78 \u2022 \u00a7b/" + label + " reload \u00a77\u2013 reload configuration");
        s.sendMessage("\u00a78 \u2022 \u00a7b/" + label + " purge <player> confirm \u00a77\u2013 delete history (separate permission)");
    }

    private void withTarget(CommandSender s, String[] a, String perm, BiConsumer<CommandSender, Target> action) {
        if (perm != null && !s.hasPermission(perm)) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cNo permission (" + perm + ").");
            return;
        }
        if (a.length < 2) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cPlease specify a player name.");
            return;
        }
        this.resolve(a[1]).whenComplete((t, err) -> this.onSender(s, () -> {
            if (!s.hasPermission("kyroac.command") || (perm != null && !s.hasPermission(perm))) {
                s.sendMessage(P + "\u00a7cNo permission.");
                return;
            }
            if (err != null || t.isEmpty()) {
                s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cPlayer '" + a[1] + "' not found (neither online nor in the flag database).");
                return;
            }
            action.accept(s, t.get());
        }));
    }

    private void onSender(CommandSender sender, Runnable action) {
        if (sender instanceof Player viewer) {
            this.plugin.gui().onViewer(viewer, action);
        } else {
            action.run();
        }
    }

    private CompletableFuture<Optional<Target>> resolve(String name) {
        Player online = Bukkit.getPlayerExact((String)name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new Target(online.getUniqueId(), online.getName())));
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached((String)name);
        if (cached != null && cached.getName() != null) {
            return CompletableFuture.completedFuture(Optional.of(new Target(cached.getUniqueId(), cached.getName())));
        }
        if (!this.plugin.store().isAvailable()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return this.plugin.store().findByName(name).thenApply(o -> o.map(e -> new Target((UUID)e.getKey(), (String)e.getValue())));
    }

    private void info(CommandSender s, Target t) {
        long now = System.currentTimeMillis();
        GuiText text = this.plugin.gui().text();
        Player online = Bukkit.getPlayer((UUID)t.uuid());
        s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7fInfo: \u00a7b" + t.name() + " \u00a78(" + (online != null ? "\u00a7aonline" : "\u00a78offline") + "\u00a78)");
        this.sendVl(s, t, now);
        if (online != null) {
            online.getScheduler().run((Plugin)this.plugin, task -> {
                PlayerData d = this.plugin.data(online.getUniqueId());
                String bypass = this.plugin.exemptions().bypassReason(online);
                PlayerHistory.Summary h = d == null ? null : d.history.summary(System.currentTimeMillis(), 2000L);
                ArrayList<String> lines = new ArrayList<String>();
                lines.add("\u00a77Ping: \u00a7f" + online.getPing() + " ms \u00a78| \u00a77Gamemode: \u00a7f" + String.valueOf(online.getGameMode()));
                lines.add("\u00a77Active exemption: \u00a7f" + (bypass != null ? bypass : (d != null && d.lastExemptReason != null ? d.lastExemptReason : "none")));
                if (d != null) {
                    lines.add(String.format(Locale.ROOT, "\u00a77Buffers: \u00a7fSpeed %.2f \u00a78| \u00a7fFly %.2f \u00a78| \u00a7fTimer %+.0fms \u00a78| \u00a7fReach %.2f \u00a78| \u00a7fBoat %.2f \u00a78| \u00a7fSpoof %.1f", d.speedBuffer, d.flyBuffer, d.timerBalance, d.reachBuffer, d.boatFlyBuffer, d.groundSpoofBuffer));
                    lines.add("\u00a77Bedrock: \u00a7f" + (d.bedrock == null ? "unknown" : (d.bedrock != false ? "yes (exempt)" : "no")) + " \u00a78| \u00a77Legacy counters Fly/Boat: \u00a7f" + d.flyFlags.size() + "/" + d.boatFlyFlags.size());
                    lines.add(String.format(Locale.ROOT, "\u00a77Position sync: \u00a7f%d relocations, %d corrections, %d plugin redirects \u00a78| \u00a77Setbacks: \u00a7f%d requested, %d completed, %d failed, %d skipped (in flight)", d.relocations, d.corrections, d.pluginRedirects, d.setbacksRequested, d.setbacksCompleted, d.setbacksFailed, d.setbacksSkippedInFlight));
                }
                if (h != null) {
                    lines.add(String.format(Locale.ROOT, "\u00a77Last 2 s: \u00a7f%d moves, h_max %.3f, h_avg %.3f, dy %.3f/%.3f, %d attacks on %d targets", h.samples(), h.maxHorizontal(), h.avgHorizontal(), h.maxUp(), h.maxDown(), h.attacks(), h.distinctTargets()));
                }
                this.onSender(s, () -> {
                    if (s.hasPermission("kyroac.command")) {
                        lines.forEach(s::sendMessage);
                    }
                });
            }, null);
        }
        if (!this.plugin.store().isAvailable()) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cFlag database unavailable.");
            return;
        }
        this.plugin.store().stats(t.uuid()).thenCombine(this.plugin.store().recentFlags(t.uuid(), 5), (stats, recent) -> {
            List<String> lines = new ArrayList<>();
            lines.add("\u00a77Stored flags: \u00a7f" + stats.total() + " \u00a78(" + stats.triggers() + " triggers) \u00a77| New: \u00a7c" + stats.newCount() + " \u00a77| Reviewed: \u00a7e" + stats.reviewedCount() + " \u00a77| Resolved: \u00a7a" + stats.resolvedCount());
            if (recent.isEmpty()) {
                lines.add("\u00a77Recent flags: \u00a7anone");
            } else {
                lines.add("\u00a77Recent flags:");
                for (FlagRecord f : recent) {
                    lines.add(String.format(Locale.ROOT, "\u00a78 \u2022 \u00a7f%s \u00a78[%s\u00a78] \u00a77VL \u00a7c%s \u00a78| %s \u00a78| %s \u00a78| \u00a77%s", text.time(f.createdAt()), f.category().displayName(), GuiText.vl(f.vl()), f.confidence().colored(), f.status().colored(), f.checkName() + ": " + f.reason()));
                }
            }
            return lines;
        }).whenComplete((lines, error) -> this.onSender(s, () -> {
            if (!s.hasPermission("kyroac.command")) {
                return;
            }
            if (error != null) {
                s.sendMessage(P + "\u00a7cCould not load flags.");
            } else {
                lines.forEach(s::sendMessage);
            }
        }));
    }

    private void violations(CommandSender s, Target t) {
        s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7fCurrent VL: \u00a7b" + t.name());
        this.sendVl(s, t, System.currentTimeMillis());
    }

    private void sendVl(CommandSender s, Target t, long now) {
        Map<String, Double> vls = this.plugin.levels().snapshot(t.uuid(), now);
        if (vls.isEmpty()) {
            s.sendMessage("\u00a77Current VL: \u00a7anone");
            return;
        }
        s.sendMessage("\u00a77Current VL \u00a78(decay " + this.plugin.getConfig().getDouble("violations.decay-per-minute", 1.0) + "/min):");
        vls.forEach((check, vl) -> s.sendMessage("\u00a78 \u2022 \u00a7f" + check + " \u00a78[" + this.plugin.registry().get((String)check).category().displayName() + "\u00a78] \u00a7c" + GuiText.vl(vl)));
    }

    private void history(CommandSender s, String[] a) {
        if (!(s instanceof Player)) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cIn-game only. Use /kyro info <player>.");
            return;
        }
        Player viewer = (Player)s;
        if (!viewer.hasPermission("kyroac.sus")) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cNo permission (kyroac.sus).");
            return;
        }
        this.withTarget(s, a, "kyroac.sus", (sender, t) -> this.plugin.gui().onViewer(viewer, () -> this.plugin.gui().playerFlags().open(viewer, PlayerFlagsGui.State.initial(t.uuid(), t.name(), SusGui.State.initial()))));
    }

    private void reset(CommandSender s, Target t) {
        UUID uUID;
        this.plugin.flags().resetCurrent(t.uuid());
        Player online = Bukkit.getPlayer((UUID)t.uuid());
        if (online != null) {
            online.getScheduler().run((Plugin)this.plugin, task -> {
                PlayerData d = this.plugin.data(online.getUniqueId());
                if (d != null) {
                    d.resetBuffers();
                }
            }, null);
        }
        if (s instanceof Player) {
            Player p = (Player)s;
            uUID = p.getUniqueId();
        } else {
            uUID = null;
        }
        UUID actor = uUID;
        this.plugin.store().audit(actor, s.getName(), "RESET_VL", t.uuid(), "name=" + t.name());
        this.plugin.getLogger().info(s.getName() + " reset the current VL of " + t.name() + ".");
        s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7aCurrent VL and buffers of \u00a7f" + t.name() + " \u00a7ahave been reset. \u00a77The flag history is kept.");
    }

    private void purge(CommandSender s, String[] a) {
        if (!(s instanceof ConsoleCommandSender) && !s.hasPermission("kyroac.purge")) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cNo permission (kyroac.purge).");
            return;
        }
        if (a.length < 3 || !a[2].equalsIgnoreCase("confirm")) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cThis permanently deletes the entire flag history.");
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a77Confirm with: \u00a7f/kyro purge " + (a.length > 1 ? a[1] : "<player>") + " confirm");
            return;
        }
        this.withTarget(s, a, s instanceof ConsoleCommandSender ? null : "kyroac.purge", (sender, t) -> {
            UUID uUID;
            if (s instanceof Player) {
                Player p = (Player)s;
                uUID = p.getUniqueId();
            } else {
                uUID = null;
            }
            UUID actor = uUID;
            this.plugin.store().deletePlayer(t.uuid(), actor, s.getName(), System.currentTimeMillis()).whenComplete((n, err) -> {
                if (err != null) {
                    s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cDeletion failed \u2013 nothing was changed.");
                    return;
                }
                this.plugin.flags().clearHistoryReferences(t.uuid());
                this.plugin.getLogger().warning(s.getName() + " purged " + n + " flag records of " + t.name() + ".");
                s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7a" + n + " flag records of \u00a7f" + t.name() + " \u00a7adeleted. \u00a77(logged)");
            });
        });
    }

    private void reload(CommandSender s) {
        UUID uUID;
        if (!s.hasPermission("kyroac.reload")) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cNo permission (kyroac.reload).");
            return;
        }
        List<String> messages = this.plugin.reloadAll();
        if (s instanceof Player) {
            Player p = (Player)s;
            uUID = p.getUniqueId();
        } else {
            uUID = null;
        }
        UUID actor = uUID;
        this.plugin.store().audit(actor, s.getName(), "RELOAD", null, "warnings=" + messages.size());
        if (messages.isEmpty()) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7aConfiguration reloaded, no problems found.");
        } else {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7eConfiguration reloaded with " + messages.size() + " warning(s):");
            messages.forEach(m -> s.sendMessage("\u00a78 \u2022 \u00a7e" + m));
        }
    }

    private void checks(CommandSender s) {
        s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7fRegistered checks \u00a78(" + this.plugin.registry().all().size() + "):");
        for (CheckInfo c : this.plugin.registry().all()) {
            boolean enabled = this.plugin.getConfig().getBoolean(c.enabledPath(), true);
            s.sendMessage("\u00a78 \u2022 " + (enabled ? "\u00a7a\u25cf " : "\u00a7c\u25cb ") + "\u00a7f" + c.name() + " \u00a78[" + c.category().displayName() + "\u00a78] \u00a77" + c.enabledPath() + "=" + enabled);
            if (!s.hasPermission("kyroac.debug")) continue;
            s.sendMessage("\u00a78     Limits: \u00a77" + c.limitations());
        }
        s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a77Database: " + (this.plugin.store().isAvailable() ? "\u00a7aOK" : "\u00a7cunavailable") + " \u00a78| \u00a77queue: \u00a7f" + this.plugin.store().queueSize() + " \u00a78| \u00a77dropped: \u00a7f" + this.plugin.store().droppedWrites() + " \u00a78| \u00a77errors: \u00a7f" + this.plugin.store().failedOperations());
    }

    private void debug(CommandSender s, String[] a) {
        if (!(s instanceof Player)) {
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77\u00a7cDebug output goes to chat and is in-game only.");
            return;
        }
        Player viewer = (Player)s;
        this.withTarget(s, a, "kyroac.debug", (sender, t) -> {
            boolean on = this.plugin.debug().toggle(viewer.getUniqueId(), t.uuid());
            s.sendMessage("\u00a78[\u00a7bKYRO\u00a78] \u00a77Debug for \u00a7f" + t.name() + " " + (on ? "\u00a7aenabled" : "\u00a7cdisabled") + (on ? " \u00a78(movement values throttled, flags and exemption changes instantly)" : ""));
        });
    }

    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("kyroac.command")) {
            return List.of();
        }
        if (a.length == 1) {
            return KyroCommand.filter(SUBS, a[0]);
        }
        if (a.length == 2 && !a[0].equalsIgnoreCase("reload") && !a[0].equalsIgnoreCase("checks")) {
            ArrayList<String> names = new ArrayList<String>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return KyroCommand.filter(names, a[1]);
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("purge")) {
            return KyroCommand.filter(List.of("confirm"), a[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        ArrayList<String> out = new ArrayList<String>();
        for (String o : options) {
            if (!o.toLowerCase(Locale.ROOT).startsWith(p)) continue;
            out.add(o);
        }
        return out;
    }

    private record Target(UUID uuid, String name) {
    }
}

