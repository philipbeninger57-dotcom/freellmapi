package de.kyrosmp.anticheat.core;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseEffects;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ItemUseSpeed {
    public static final double VANILLA = 0.2;
    private static volatile boolean available = true;

    private ItemUseSpeed() {}

    public static double of(Player p) {
        if (!available) {
            return 0.2;
        } else {
            try {
                ItemStack item = p.getActiveItem();
                if (item != null && !item.isEmpty()) {
                    UseEffects effects = (UseEffects) item.getData(DataComponentTypes.USE_EFFECTS);
                    if (effects == null) {
                        return 0.2;
                    } else {
                        double speed = (double) effects.speedMultiplier();
                        return Double.isFinite(speed) ? Math.max(0.0, speed) : 0.2;
                    }
                } else {
                    return 0.2;
                }
            } catch (LinkageError ignored) {
                available = false;
                return 0.2;
            } catch (Throwable ignored) {
                return 0.2;
            }
        }
    }
}
