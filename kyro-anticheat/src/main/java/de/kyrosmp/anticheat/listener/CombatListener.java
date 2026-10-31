package de.kyrosmp.anticheat.listener;

import de.kyrosmp.anticheat.checks.action.InventoryMoveCheck;
import de.kyrosmp.anticheat.checks.combat.AutoClickerCheck;
import de.kyrosmp.anticheat.checks.combat.CombatChecks;
import de.kyrosmp.anticheat.checks.combat.CriticalsCheck;
import de.kyrosmp.anticheat.checks.combat.NoSwingCheck;
import de.kyrosmp.anticheat.core.BedrockDetector;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerDataStore;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.gui.MenuHolder;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;

import java.util.function.Supplier;

public final class CombatListener implements Listener {
    private final PlayerDataStore store;
    private final Supplier<CombatListener.Checks> checks;
    private final Supplier<CheckConfig> cfg;
    private volatile Supplier<StrictConfig> strict = StrictConfig::defaults;
    private volatile Supplier<PreventionConfig> prevention = PreventionConfig::defaults;

    public CombatListener(
            PlayerDataStore store,
            Supplier<CombatListener.Checks> checks,
            Supplier<CheckConfig> cfg) {
        this.store = store;
        this.checks = checks;
        this.cfg = cfg;
    }

    public CombatListener withPolicy(
            Supplier<StrictConfig> strict, Supplier<PreventionConfig> prevention) {
        this.strict = strict;
        this.prevention = prevention;
        return this;
    }

    private boolean exempt(Player p, PlayerData d) {
        return p.hasPermission("kyroac.bypass")
                || this.cfg.get().bedrockExempt() && BedrockDetector.isBedrock(p, d);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)
                || !(e.getEntity() instanceof LivingEntity target)) {
            return;
        }

        if (e.getCause() == DamageCause.ENTITY_ATTACK) {
            if (!p.hasPermission("kyroac.bypass")) {
                PlayerData d = this.store.get(p);
                if ((!this.cfg.get().bedrockExempt() || !BedrockDetector.isBedrock(p, d))
                        && p.getGameMode() != GameMode.CREATIVE) {
                    PlayerData playerData;
                    if (target instanceof Player tp) {
                        playerData = this.store.peek(tp.getUniqueId());
                    } else {
                        playerData = null;
                    }

                    long now = System.currentTimeMillis();
                    CombatListener.Checks c = this.checks.get();
                    if (c.combat().onAttack(p, d, target, playerData, now).cancel()) {
                        e.setCancelled(true);
                    }

                    if (c.noSwing() != null && c.noSwing().onAttack(p, d)) {
                        e.setCancelled(true);
                    }

                    if (!e.isCancelled()
                            && c.autoClicker() != null
                            && c.autoClicker().shouldCancelHit(d, now)) {
                        e.setCancelled(true);
                    }

                    boolean critical;
                    try {
                        critical = e.isCritical();
                    } catch (Throwable ignored) {
                        critical = false;
                    }

                    if (critical && !e.isCancelled() && c.criticals() != null) {
                        boolean fake = c.criticals().onCritical(p, d, now);
                        if (fake
                                && this.strict.get().criticalsPrevent()
                                && this.prevention.get().actions()) {
                            e.setDamage(e.getDamage() / 1.5);
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceSmash(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player player) {
            DamageSource source = e.getDamageSource();
            if (source != null
                    && source.getDamageType() != null
                    && NamespacedKey.minecraft("mace_smash")
                            .equals(source.getDamageType().getKey())) {
                long now = System.currentTimeMillis();
                PlayerData d = this.store.getQuiet(player);
                d.lastMaceSmashAt = now;
                d.registerPredictionImpulse(1.0, 0.01, 0.0, now);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        this.store.get(e.getPlayer()).lastDrop = System.currentTimeMillis();
    }

    /** Counts every swing packet (also ones other plugins cancel) for KillAura D. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnySwing(PlayerAnimationEvent e) {
        PlayerAnimationType type = e.getAnimationType();
        if (type == PlayerAnimationType.ARM_SWING || type == PlayerAnimationType.OFF_ARM_SWING) {
            NoSwingCheck.onSwing(this.store.getQuiet(e.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            Player p = e.getPlayer();
            PlayerData d = this.store.get(p);
            long now = System.currentTimeMillis();
            d.lastSwingAt = now;
            if (!this.exempt(p, d)) {
                this.checks.get().autoClicker().onSwing(p, d, now);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) {
            this.checks.get().inventoryMove().onOpen(this.store.get(p), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            this.checks.get().inventoryMove().onClose(this.store.get(p));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            if (!(e.getView().getTopInventory().getHolder(false) instanceof MenuHolder)) {
                PlayerData d = this.store.get(p);
                if (!this.exempt(p, d)) {
                    boolean moving =
                            this.checks
                                    .get()
                                    .inventoryMove()
                                    .onClick(p, d, System.currentTimeMillis());
                    if (moving
                            && this.strict.get().inventoryMoveCancel()
                            && this.prevention.get().actions()) {
                        e.setCancelled(true);
                    }
                }
            }
        }
    }

    public static record Checks(
            CombatChecks combat,
            AutoClickerCheck autoClicker,
            InventoryMoveCheck inventoryMove,
            CriticalsCheck criticals,
            NoSwingCheck noSwing) {
        public Checks(
                CombatChecks combat,
                AutoClickerCheck autoClicker,
                InventoryMoveCheck inventoryMove,
                CriticalsCheck criticals) {
            this(combat, autoClicker, inventoryMove, criticals, null);
        }

        public Checks(
                CombatChecks combat,
                AutoClickerCheck autoClicker,
                InventoryMoveCheck inventoryMove) {
            this(combat, autoClicker, inventoryMove, null, null);
        }
    }
}
