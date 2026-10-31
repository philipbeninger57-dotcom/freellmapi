/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.OfflinePlayer
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandSender
 *  org.bukkit.command.TabExecutor
 *  org.bukkit.entity.Player
 *  org.bukkit.plugin.Plugin
 */
package de.kyrosmp.anticheat.command;

import de.kyrosmp.anticheat.KyroAntiCheat;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.flag.FlagStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SusCommand
implements TabExecutor {
    private static final String P = "\u00a78[\u00a75KYRO-AC\u00a78] \u00a77";
    private final KyroAntiCheat plugin;

    public SusCommand(KyroAntiCheat plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("acalerts")) {
            if (!(s instanceof Player)) {
                s.sendMessage("\u00a78[\u00a75KYRO-AC\u00a78] \u00a77Player only.");
                return true;
            }
            Player p = (Player)s;
            if (!p.hasPermission("kyroac.alerts")) {
                p.sendMessage("\u00a78[\u00a75KYRO-AC\u00a78] \u00a77\u00a7cNo permission.");
                return true;
            }
            boolean on = this.plugin.alerts().toggle(p);
            p.sendMessage(P + (on ? "\u00a7aAntiCheat alerts enabled." : "\u00a7cAntiCheat alerts disabled."));
            return true;
        }
        if (!s.hasPermission("kyroac.sus")) {
            s.sendMessage("\u00a78[\u00a75KYRO-AC\u00a78] \u00a77\u00a7cNo permission.");
            return true;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("\u00a78[\u00a75KYRO-AC\u00a78] \u00a77Player only.");
            return true;
        }
        Player viewer = (Player)s;
        if (a.length >= 2 && a[0].equalsIgnoreCase("clear")) {
            this.clear(viewer, a[1]);
            return true;
        }
        if (a.length >= 1) {
            Player online = Bukkit.getPlayerExact((String)a[0]);
            if (online == null) {
                viewer.sendMessage("\u00a78[\u00a75KYRO-AC\u00a78] \u00a77\u00a7cPlayer not found.");
                return true;
            }
            this.plugin.gui().investigate(viewer, online.getUniqueId(), online.getName());
            return true;
        }
        this.plugin.gui().sus().open(viewer);
        return true;
    }

    private void clear(Player viewer, String name) {
        if (!viewer.hasPermission("kyroac.clear")) {
            viewer.sendMessage("\u00a78[\u00a75KYRO-AC\u00a78] \u00a77\u00a7cNo permission.");
            return;
        }
        UUID id = null;
        String display = name;
        Player online = Bukkit.getPlayerExact((String)name);
        if (online != null) {
            id = online.getUniqueId();
            display = online.getName();
        } else {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached((String)name);
            if (cached != null) {
                id = cached.getUniqueId();
            }
        }
        if (id == null) {
            this.plugin.store().findByName(name).whenComplete((o, err) -> this.plugin.gui().onViewer(viewer, () -> {
                if (err != null || o.isEmpty()) {
                    viewer.sendMessage("\u00a78[\u00a75KYRO-AC\u00a78] \u00a77\u00a7cPlayer not found.");
                } else {
                    this.doClear(viewer, o.get().getKey(), o.get().getValue());
                }
            }));
            return;
        }
        this.doClear(viewer, id, display);
    }

    private void doClear(Player viewer, UUID id, String name) {
        if (!viewer.hasPermission("kyroac.sus") || !viewer.hasPermission("kyroac.clear")) {
            viewer.sendMessage(P + "\u00a7cNo permission.");
            return;
        }
        UUID actor = viewer.getUniqueId();
        String actorName = viewer.getName();
        this.plugin.store().updatePlayerStatus(id, List.of(FlagStatus.NEW, FlagStatus.REVIEWED), FlagStatus.RESOLVED, actor, actorName, "SUS_CLEAR", System.currentTimeMillis()).whenComplete((n, err) -> {
            if (err != null) {
                this.plugin.gui().onViewer(viewer, () -> viewer.sendMessage(P + "\u00a7cCould not update flags; current VL and buffers were kept."));
                return;
            }
            // Keep live and stored state together: only reset after the database commit.
            this.plugin.flags().resetCurrent(id);
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                online.getScheduler().run(this.plugin, task -> {
                    PlayerData data = this.plugin.data(id);
                    if (data != null) data.resetBuffers();
                }, null);
            }
            this.plugin.gui().onViewer(viewer, () -> viewer.sendMessage(P + "Flags cleared for \u00a7f" + name + " \u00a78(" + n + " marked resolved, history is kept)"));
        });
        this.plugin.getLogger().info(viewer.getName() + " ran /sus clear for " + name + ".");
    }

    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("acalerts") || !s.hasPermission("kyroac.sus")) {
            return List.of();
        }
        ArrayList<String> names = new ArrayList<String>();
        if (a.length == 1) {
            if (s.hasPermission("kyroac.clear")) {
                names.add("clear");
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return SusCommand.filter(names, a[0]);
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("clear") && s.hasPermission("kyroac.clear")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return SusCommand.filter(names, a[1]);
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
}

