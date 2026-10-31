/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.entity.Player
 *  org.bukkit.plugin.Plugin
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.flag.Confidence;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class AlertManager {
    public static final String PREFIX = "\u00a78[\u00a75KYRO-AC\u00a78] \u00a77";
    private final Plugin plugin;
    private final Logger log;
    private final Set<UUID> disabled = ConcurrentHashMap.newKeySet();
    private volatile boolean console = true;
    private static final int MAX_PENDING_ALERTS = 256;
    private final Object batchLock = new Object();
    private final List<String> pending = new ArrayList<>();
    private boolean flushScheduled;
    private long overflow;
    // Hooks keep scheduling independently testable without a running server.
    Consumer<Runnable> scheduleGlobal;
    Supplier<Collection<? extends Player>> onlinePlayers = Bukkit::getOnlinePlayers;

    public AlertManager(Plugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
        this.scheduleGlobal = action -> Bukkit.getGlobalRegionScheduler().execute(plugin, action);
    }

    public void setConsole(boolean console) {
        this.console = console;
    }

    public boolean toggle(Player p) {
        if (this.disabled.remove(p.getUniqueId())) {
            return true;
        }
        this.disabled.add(p.getUniqueId());
        return false;
    }

    public void flagAlert(String player, String check, double vl, Confidence confidence, String details, int ping) {
        this.send(String.format(Locale.US, "\u00a7f%s \u00a77flagged \u00a7d%s \u00a78(\u00a7fVL %.1f\u00a78, %s\u00a78) \u00a78%s \u00a78[%dms]", player, check, vl, confidence.colored(), details, ping));
    }

    public void send(String message) {
        String msg = ChatColor.translateAlternateColorCodes('&', PREFIX + message);
        // Console evidence remains immediate even if staff delivery is overloaded.
        if (this.console) {
            this.log.info(ChatColor.stripColor(msg));
        }
        synchronized (this.batchLock) {
            if (this.pending.size() < MAX_PENDING_ALERTS) {
                this.pending.add(msg);
            } else {
                ++this.overflow;
            }
            if (this.flushScheduled) {
                return;
            }
            this.flushScheduled = true;
        }
        try {
            this.scheduleGlobal.accept(this::flush);
        } catch (RuntimeException ex) {
            long lost;
            synchronized (this.batchLock) {
                lost = this.pending.size() + this.overflow;
                this.pending.clear();
                this.overflow = 0;
                this.flushScheduled = false;
            }
            this.log.warning("Could not schedule " + lost + " staff alerts: " + ex.getClass().getSimpleName());
        }
    }

    private void flush() {
        List<String> batch;
        long dropped;
        synchronized (this.batchLock) {
            batch = new ArrayList<>(this.pending);
            this.pending.clear();
            dropped = this.overflow;
            this.overflow = 0;
            this.flushScheduled = false;
        }
        if (dropped > 0) {
            String summary = dropped + " additional staff alerts omitted during overload; review flag history.";
            batch.add(PREFIX + summary);
            this.log.warning(summary);
        }
        String[] messages = batch.toArray(String[]::new);
        for (Player p : this.onlinePlayers.get()) {
            try {
                p.getScheduler().run(this.plugin, task -> {
                    // Permissions and alert preferences may change while tasks wait.
                    if (p.hasPermission("kyroac.alerts") && !this.disabled.contains(p.getUniqueId())) {
                        p.sendMessage(messages);
                    }
                }, null);
            } catch (RuntimeException ex) {
                this.log.warning("Could not schedule staff alert batch for " + p.getUniqueId()
                        + ": " + ex.getClass().getSimpleName());
            }
        }
    }
}
