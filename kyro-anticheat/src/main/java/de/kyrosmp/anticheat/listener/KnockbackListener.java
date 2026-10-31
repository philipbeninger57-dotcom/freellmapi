/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.papermc.paper.event.entity.EntityKnockbackEvent
 *  io.papermc.paper.event.entity.EntityKnockbackEvent$Cause
 *  org.bukkit.entity.Entity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.util.Vector
 */
package de.kyrosmp.anticheat.listener;

import de.kyrosmp.anticheat.checks.combat.VelocityCheck;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerDataStore;
import de.kyrosmp.anticheat.listener.MovementListener;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import java.util.function.Supplier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

public final class KnockbackListener
implements Listener {
    private final PlayerDataStore store;
    private final Supplier<VelocityCheck> velocity;
    private final Supplier<CheckConfig> cfg;

    public KnockbackListener(PlayerDataStore store, Supplier<VelocityCheck> velocity, Supplier<CheckConfig> cfg) {
        this.store = store;
        this.velocity = velocity;
        this.cfg = cfg;
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onKnockback(EntityKnockbackEvent e) {
        Entity entity = e.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }
        Player p = (Player)entity;
        PlayerData d = this.store.get(p);
        long now = System.currentTimeMillis();
        double strength = 1.0;
        Vector kb = null;
        try {
            kb = e.getKnockback();
            strength = Math.max(1.0, kb.length());
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        MovementListener.applyVelocityGrace(d, strength, now, this.cfg.get());
        if (kb != null) {
            MovementListener.registerImpulse(d, kb.getY(), now);
            d.registerPredictionImpulse(kb.getX(), kb.getY(), kb.getZ(), now);
        }
        try {
            if (e.getCause() == EntityKnockbackEvent.Cause.EXPLOSION) {
                double y = p.getLocation().getY();
                d.impulseY = Double.isNaN(d.impulseY) ? y : Math.max(d.impulseY, y);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (!p.hasPermission("kyroac.bypass")) {
            this.velocity.get().onKnockback(p, d, kb, now);
        }
    }
}

