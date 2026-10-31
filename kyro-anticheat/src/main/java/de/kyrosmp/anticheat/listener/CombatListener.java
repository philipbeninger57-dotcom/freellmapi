/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.GameMode
 *  org.bukkit.entity.Entity
 *  org.bukkit.entity.HumanEntity
 *  org.bukkit.entity.LivingEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.event.entity.EntityDamageByEntityEvent
 *  org.bukkit.event.entity.EntityDamageEvent$DamageCause
 *  org.bukkit.event.inventory.InventoryClickEvent
 *  org.bukkit.event.player.PlayerAnimationEvent
 *  org.bukkit.event.player.PlayerAnimationType
 *  org.bukkit.event.player.PlayerDropItemEvent
 */
package de.kyrosmp.anticheat.listener;

import de.kyrosmp.anticheat.checks.action.InventoryMoveCheck;
import de.kyrosmp.anticheat.checks.combat.AutoClickerCheck;
import de.kyrosmp.anticheat.checks.combat.CombatChecks;
import de.kyrosmp.anticheat.checks.combat.CriticalsCheck;
import de.kyrosmp.anticheat.core.BedrockDetector;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerDataStore;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.gui.MenuHolder;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;

public final class CombatListener
implements Listener {
    private final PlayerDataStore store;
    private final Supplier<Checks> checks;
    private final Supplier<CheckConfig> cfg;

    public CombatListener(PlayerDataStore store, Supplier<Checks> checks, Supplier<CheckConfig> cfg) {
        this.store = store;
        this.checks = checks;
        this.cfg = cfg;
    }

    private volatile Supplier<StrictConfig> strict = StrictConfig::defaults;
    private volatile Supplier<PreventionConfig> prevention = PreventionConfig::defaults;

    /** 3.7 prevention settings (critical stripping, click prevention). */
    public CombatListener withPolicy(Supplier<StrictConfig> strict, Supplier<PreventionConfig> prevention) {
        this.strict = strict;
        this.prevention = prevention;
        return this;
    }

    private boolean exempt(Player p, PlayerData d) {
        return p.hasPermission("kyroac.bypass") || this.cfg.get().bedrockExempt() && BedrockDetector.isBedrock(p, d);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onAttack(EntityDamageByEntityEvent e) {
        boolean critical;
        PlayerData playerData;
        Entity entity;
        Player p;
        block13: {
            block12: {
                Entity damager = e.getDamager();
                if (!(damager instanceof Player)) break block12;
                p = (Player)damager;
                entity = e.getEntity();
                if (entity instanceof LivingEntity) break block13;
            }
            return;
        }
        LivingEntity target = (LivingEntity)entity;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (p.hasPermission("kyroac.bypass")) {
            return;
        }
        PlayerData d = this.store.get(p);
        if (this.cfg.get().bedrockExempt() && BedrockDetector.isBedrock(p, d) || p.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (target instanceof Player) {
            Player tp = (Player)target;
            playerData = this.store.peek(tp.getUniqueId());
        } else {
            playerData = null;
        }
        PlayerData targetData = playerData;
        long now = System.currentTimeMillis();
        Checks c = this.checks.get();
        if (c.combat().onAttack(p, d, target, targetData, now).cancel()) {
            e.setCancelled(true);
        }
        // 3.7: no damage from hits while the click rate is beyond what a human can sustain.
        if (!e.isCancelled() && c.autoClicker() != null && c.autoClicker().shouldCancelHit(d, now)) {
            e.setCancelled(true);
        }
        try {
            critical = e.isCritical();
        }
        catch (Throwable ex) {
            critical = false;
        }
        if (critical && !e.isCancelled() && c.criticals() != null) {
            boolean fake = c.criticals().onCritical(p, d, now);
            // 3.7: a critical without a real fall loses its bonus (vanilla multiplies by 1.5).
            if (fake && this.strict.get().criticalsPrevent() && this.prevention.get().actions()) {
                e.setDamage(e.getDamage() / 1.5);
            }
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onMaceSmash(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player player)) return;
        org.bukkit.damage.DamageSource source = e.getDamageSource();
        if (source != null && source.getDamageType() != null
                && org.bukkit.NamespacedKey.minecraft("mace_smash").equals(source.getDamageType().getKey())) {
            long now = System.currentTimeMillis();
            PlayerData d = this.store.getQuiet(player);
            d.lastMaceSmashAt = now;
            // The server resets the attacker's vertical motion to 0.01 and sends its horizontal motion.
            d.registerPredictionImpulse(1.0, 0.01, 0.0, now);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onDrop(PlayerDropItemEvent e) {
        this.store.get((Player)e.getPlayer()).lastDrop = System.currentTimeMillis();
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onSwing(PlayerAnimationEvent e) {
        PlayerData d;
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player p = e.getPlayer();
        d = this.store.get(p);
        long now = System.currentTimeMillis();
        d.lastSwingAt = now;
        if (this.exempt(p, d)) {
            return;
        }
        this.checks.get().autoClicker().onSwing(p, d, now);
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) {
            this.checks.get().inventoryMove().onOpen(this.store.get(p), System.currentTimeMillis());
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            this.checks.get().inventoryMove().onClose(this.store.get(p));
        }
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onInventoryClick(InventoryClickEvent e) {
        HumanEntity humanEntity = e.getWhoClicked();
        if (!(humanEntity instanceof Player)) {
            return;
        }
        Player p = (Player)humanEntity;
        if (e.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
            return;
        }
        PlayerData d = this.store.get(p);
        if (this.exempt(p, d)) {
            return;
        }
        boolean moving = this.checks.get().inventoryMove().onClick(p, d, System.currentTimeMillis());
        // 3.7: inventory actions while clearly moving (inventory managers, auto armor) are refused.
        if (moving && this.strict.get().inventoryMoveCancel() && this.prevention.get().actions()) {
            e.setCancelled(true);
        }
    }

    public record Checks(CombatChecks combat, AutoClickerCheck autoClicker, InventoryMoveCheck inventoryMove, CriticalsCheck criticals) {
        public Checks(CombatChecks combat, AutoClickerCheck autoClicker, InventoryMoveCheck inventoryMove) {
            this(combat, autoClicker, inventoryMove, null);
        }
    }
}

