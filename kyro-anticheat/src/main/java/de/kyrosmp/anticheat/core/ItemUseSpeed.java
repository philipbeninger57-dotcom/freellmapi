package de.kyrosmp.anticheat.core;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Movement multiplier while an item is in use. Vanilla uses 0.2; the 1.21.x use_effects data
 * component can change it. The component API is touched only from here and only inside a guard,
 * so the plugin still loads on servers without that component.
 */
public final class ItemUseSpeed {
    public static final double VANILLA = 0.2;
    private static volatile boolean available = true;

    private ItemUseSpeed() {
    }

    public static double of(Player p) {
        if (!available) {
            return VANILLA;
        }
        try {
            ItemStack item = p.getActiveItem();
            if (item == null || item.isEmpty()) {
                return VANILLA;
            }
            io.papermc.paper.datacomponent.item.UseEffects effects =
                    item.getData(io.papermc.paper.datacomponent.DataComponentTypes.USE_EFFECTS);
            if (effects == null) {
                return VANILLA;
            }
            double speed = effects.speedMultiplier();
            return Double.isFinite(speed) ? Math.max(0.0, speed) : VANILLA;
        }
        catch (LinkageError missingApi) {
            available = false;
            return VANILLA;
        }
        catch (Throwable ex) {
            return VANILLA;
        }
    }
}
