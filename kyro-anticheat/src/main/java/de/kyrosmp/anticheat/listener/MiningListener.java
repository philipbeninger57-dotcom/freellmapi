package de.kyrosmp.anticheat.listener;

import de.kyrosmp.anticheat.checks.action.BlockBreakChecks;
import de.kyrosmp.anticheat.checks.action.XRayCheck;
import de.kyrosmp.anticheat.core.BedrockDetector;
import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.ExemptionManager;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerDataStore;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;

import java.util.function.Supplier;

public final class MiningListener implements Listener {
    private final PlayerDataStore store;
    private final ExemptionManager exemptions;
    private final Supplier<XRayCheck> xray;
    private volatile Supplier<BlockBreakChecks> digChecks = () -> null;
    private volatile Supplier<CheckConfig> cfg = CheckConfig::defaults;

    public MiningListener(
            PlayerDataStore store, ExemptionManager exemptions, Supplier<XRayCheck> xray) {
        this.store = store;
        this.exemptions = exemptions;
        this.xray = xray;
    }

    public MiningListener withDigChecks(
            Supplier<BlockBreakChecks> digChecks, Supplier<CheckConfig> cfg) {
        this.digChecks = digChecks;
        this.cfg = cfg;
        return this;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDigStart(BlockDamageEvent e) {
        BlockBreakChecks checks = this.digChecks.get();
        if (checks != null && !this.exemptions.bypass(e.getPlayer())) {
            PlayerData d = this.store.get(e.getPlayer());
            if (!this.cfg.get().bedrockExempt() || !BedrockDetector.isBedrock(e.getPlayer(), d)) {
                if (checks.onDigStart(e.getPlayer(), d, e.getBlock(), System.currentTimeMillis())) {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!this.exemptions.bypass(e.getPlayer())) {
            this.xray
                    .get()
                    .onBreak(
                            e.getPlayer(),
                            this.store.get(e.getPlayer()),
                            e.getBlock(),
                            System.currentTimeMillis());
        }
    }
}
