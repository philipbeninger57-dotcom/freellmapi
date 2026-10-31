/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.NamespacedKey
 *  org.bukkit.Registry
 *  org.bukkit.attribute.Attribute
 *  org.bukkit.attribute.AttributeInstance
 *  org.bukkit.entity.LivingEntity
 */
package de.kyrosmp.anticheat.core;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;

public record AttributeSet(Attribute movementSpeed, Attribute jumpStrength, Attribute gravity, Attribute safeFallDistance, Attribute fallDamageMultiplier, Attribute stepHeight, Attribute entityInteractionRange, Attribute blockInteractionRange) {
    public static final AttributeSet NONE = new AttributeSet(null, null, null, null, null, null, null, null);

    public static AttributeSet resolve() {
        return new AttributeSet(AttributeSet.attribute("movement_speed", "generic.movement_speed"), AttributeSet.attribute("jump_strength", "generic.jump_strength"), AttributeSet.attribute("gravity", "generic.gravity"), AttributeSet.attribute("safe_fall_distance", "generic.safe_fall_distance"), AttributeSet.attribute("fall_damage_multiplier", "generic.fall_damage_multiplier"), AttributeSet.attribute("step_height", "generic.step_height"), AttributeSet.attribute("entity_interaction_range", "player.entity_interaction_range"), AttributeSet.attribute("block_interaction_range", "player.block_interaction_range"));
    }

    private static Attribute attribute(String ... keys) {
        for (String key : keys) {
            try {
                Attribute a = (Attribute)Registry.ATTRIBUTE.get(NamespacedKey.minecraft((String)key));
                if (a == null) continue;
                return a;
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        return null;
    }

    public static double value(LivingEntity e, Attribute attribute) {
        if (attribute == null || e == null) {
            return Double.NaN;
        }
        try {
            AttributeInstance inst = e.getAttribute(attribute);
            double value = inst == null ? Double.NaN : inst.getValue();
            return Double.isFinite(value) ? value : Double.NaN;
        }
        catch (Throwable ex) {
            return Double.NaN;
        }
    }
}

