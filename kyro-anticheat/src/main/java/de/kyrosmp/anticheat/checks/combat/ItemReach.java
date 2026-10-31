package de.kyrosmp.anticheat.checks.combat;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class ItemReach {
    private static volatile boolean unavailable;

    private ItemReach() {}

    static double[] mainHand(Player p) {
        if (unavailable) {
            return null;
        } else {
            try {
                ItemStack item = p.getInventory().getItemInMainHand();
                if (item.isEmpty()) {
                    return null;
                } else {
                    AttackRange range = (AttackRange) item.getData(DataComponentTypes.ATTACK_RANGE);
                    return range == null
                            ? null
                            : new double[] {
                                (double) range.maxReach(), (double) range.hitboxMargin()
                            };
                }
            } catch (NoSuchFieldError | NoSuchMethodError | NoClassDefFoundError ignored) {
                unavailable = true;
                return null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
