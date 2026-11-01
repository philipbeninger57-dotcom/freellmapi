package de.kyrosmp.anticheat.listener;

import de.kyrosmp.anticheat.checks.combat.VelocityCheck;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerDataStore;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent.Cause;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import java.util.function.Supplier;

public final class KnockbackListener implements Listener {
    private final PlayerDataStore store;
    private final Supplier<VelocityCheck> velocity;
    private final Supplier<CheckConfig> cfg;

    public KnockbackListener(
            PlayerDataStore store, Supplier<VelocityCheck> velocity, Supplier<CheckConfig> cfg) {
        this.store = store;
        this.velocity = velocity;
        this.cfg = cfg;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent e) {
        if (e.getEntity() instanceof Player p) {
            PlayerData d = this.store.get(p);
            long now = System.currentTimeMillis();
            double strength = 1.0;
            Vector kb = null;

            try {
                kb = e.getKnockback();
                strength = Math.max(1.0, kb.length());
            } catch (Throwable ignored) {
            }

            MovementListener.applyVelocityGrace(d, strength, now, this.cfg.get());
            if (kb != null) {
                MovementListener.registerImpulse(d, kb.getY(), now);
                d.registerPredictionImpulse(kb.getX(), kb.getY(), kb.getZ(), now);
            }

            try {
                if (e.getCause() == Cause.EXPLOSION) {
                    double y = p.getLocation().getY();
                    d.impulseY = Double.isNaN(d.impulseY) ? y : Math.max(d.impulseY, y);
                }
            } catch (Throwable ignored) {
            }

            if (!p.hasPermission("kyroac.bypass")) {
                this.velocity.get().onKnockback(p, d, kb, now);
            }
        }
    }
}
