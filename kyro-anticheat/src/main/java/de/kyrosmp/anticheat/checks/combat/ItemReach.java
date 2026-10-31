/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.papermc.paper.datacomponent.DataComponentTypes
 *  io.papermc.paper.datacomponent.item.AttackRange
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.ItemStack
 */
package de.kyrosmp.anticheat.checks.combat;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class ItemReach {
    private static volatile boolean unavailable;

    private ItemReach() {
    }

    static double[] mainHand(Player p) {
        if (unavailable) {
            return null;
        }
        try {
            ItemStack item = p.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                return null;
            }
            AttackRange range = (AttackRange)item.getData(DataComponentTypes.ATTACK_RANGE);
            if (range == null) {
                return null;
            }
            return new double[]{range.maxReach(), range.hitboxMargin()};
        }
        catch (NoClassDefFoundError | NoSuchFieldError | NoSuchMethodError ex) {
            unavailable = true;
            return null;
        }
        catch (Throwable ex) {
            return null;
        }
    }
}

