package de.kyrosmp.anticheat.command;

import de.kyrosmp.anticheat.KyroAntiCheat;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.flag.FlagStatus;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class SusCommand implements TabExecutor {
    private static final String P = "§8[§5KYRO-AC§8] §7";
    private final KyroAntiCheat plugin;

    public SusCommand(KyroAntiCheat plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("acalerts")) {
            if (!(s instanceof Player p)) {
                s.sendMessage("§8[§5KYRO-AC§8] §7Player only.");
                return true;
            } else if (!p.hasPermission("kyroac.alerts")) {
                p.sendMessage("§8[§5KYRO-AC§8] §7§cNo permission.");
                return true;
            } else {
                boolean on = this.plugin.alerts().toggle(p);
                p.sendMessage(
                        "§8[§5KYRO-AC§8] §7"
                                + (on
                                        ? "§aAntiCheat alerts enabled."
                                        : "§cAntiCheat alerts disabled."));
                return true;
            }
        } else if (!s.hasPermission("kyroac.sus")) {
            s.sendMessage("§8[§5KYRO-AC§8] §7§cNo permission.");
            return true;
        } else if (!(s instanceof Player viewer)) {
            s.sendMessage("§8[§5KYRO-AC§8] §7Player only.");
            return true;
        } else if (a.length >= 2 && a[0].equalsIgnoreCase("clear")) {
            this.clear(viewer, a[1]);
            return true;
        } else if (a.length >= 1) {
            Player online = Bukkit.getPlayerExact(a[0]);
            if (online == null) {
                viewer.sendMessage("§8[§5KYRO-AC§8] §7§cPlayer not found.");
                return true;
            } else {
                this.plugin.gui().investigate(viewer, online.getUniqueId(), online.getName());
                return true;
            }
        } else {
            this.plugin.gui().sus().open(viewer);
            return true;
        }
    }

    private void clear(Player viewer, String name) {
        if (!viewer.hasPermission("kyroac.clear")) {
            viewer.sendMessage("§8[§5KYRO-AC§8] §7§cNo permission.");
        } else {
            UUID id = null;
            String display = name;
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                id = online.getUniqueId();
                display = online.getName();
            } else {
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
                if (cached != null) {
                    id = cached.getUniqueId();
                }
            }

            if (id == null) {
                this.plugin
                        .store()
                        .findByName(name)
                        .whenComplete(
                                (o, err) ->
                                        this.plugin
                                                .gui()
                                                .onViewer(
                                                        viewer,
                                                        () -> {
                                                            if (err == null && !o.isEmpty()) {
                                                                this.doClear(
                                                                        viewer,
                                                                        o.get().getKey(),
                                                                        o.get().getValue());
                                                            } else {
                                                                viewer.sendMessage(
                                                                        "§8[§5KYRO-AC§8] §7§cPlayer"
                                                                                + " not found.");
                                                            }
                                                        }));
            } else {
                this.doClear(viewer, id, display);
            }
        }
    }

    private void doClear(Player viewer, UUID id, String name) {
        if (viewer.hasPermission("kyroac.sus") && viewer.hasPermission("kyroac.clear")) {
            UUID actor = viewer.getUniqueId();
            String actorName = viewer.getName();
            this.plugin
                    .store()
                    .updatePlayerStatus(
                            id,
                            List.of(FlagStatus.NEW, FlagStatus.REVIEWED),
                            FlagStatus.RESOLVED,
                            actor,
                            actorName,
                            "SUS_CLEAR",
                            System.currentTimeMillis())
                    .whenComplete(
                            (n, err) -> {
                                if (err != null) {
                                    this.plugin
                                            .gui()
                                            .onViewer(
                                                    viewer,
                                                    () ->
                                                            viewer.sendMessage(
                                                                    "§8[§5KYRO-AC§8] §7§cCould not"
                                                                        + " update flags; current"
                                                                        + " VL and buffers were"
                                                                        + " kept."));
                                } else {
                                    this.plugin.flags().resetCurrent(id);
                                    Player online = Bukkit.getPlayer(id);
                                    if (online != null) {
                                        online.getScheduler()
                                                .run(
                                                        this.plugin,
                                                        task -> {
                                                            PlayerData data = this.plugin.data(id);
                                                            if (data != null) {
                                                                data.resetBuffers();
                                                            }
                                                        },
                                                        null);
                                    }

                                    this.plugin
                                            .gui()
                                            .onViewer(
                                                    viewer,
                                                    () ->
                                                            viewer.sendMessage(
                                                                    "§8[§5KYRO-AC§8] §7Flags"
                                                                            + " cleared for §f"
                                                                            + name
                                                                            + " §8("
                                                                            + n
                                                                            + " marked resolved,"
                                                                            + " history is kept)"));
                                }
                            });
            this.plugin.getLogger().info(viewer.getName() + " ran /sus clear for " + name + ".");
        } else {
            viewer.sendMessage("§8[§5KYRO-AC§8] §7§cNo permission.");
        }
    }

    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!cmd.getName().equalsIgnoreCase("acalerts") && s.hasPermission("kyroac.sus")) {
            ArrayList<String> names = new ArrayList<>();
            if (a.length == 1) {
                if (s.hasPermission("kyroac.clear")) {
                    names.add("clear");
                }

                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }

                return filter(names, a[0]);
            } else if (a.length == 2
                    && a[0].equalsIgnoreCase("clear")
                    && s.hasPermission("kyroac.clear")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }

                return filter(names, a[1]);
            } else {
                return List.of();
            }
        } else {
            return List.of();
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
}
