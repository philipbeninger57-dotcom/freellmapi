package de.kyrosmp.anticheat.core;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;

public record AttributeSet(
        Attribute movementSpeed,
        Attribute jumpStrength,
        Attribute gravity,
        Attribute safeFallDistance,
        Attribute fallDamageMultiplier,
        Attribute stepHeight,
        Attribute entityInteractionRange,
        Attribute blockInteractionRange,
        Attribute sneakingSpeed) {
    public static final AttributeSet NONE =
            new AttributeSet(null, null, null, null, null, null, null, null, null);

    public static AttributeSet resolve() {
        return new AttributeSet(
                attribute("movement_speed", "generic.movement_speed"),
                attribute("jump_strength", "generic.jump_strength"),
                attribute("gravity", "generic.gravity"),
                attribute("safe_fall_distance", "generic.safe_fall_distance"),
                attribute("fall_damage_multiplier", "generic.fall_damage_multiplier"),
                attribute("step_height", "generic.step_height"),
                attribute("entity_interaction_range", "player.entity_interaction_range"),
                attribute("block_interaction_range", "player.block_interaction_range"),
                attribute("sneaking_speed", "player.sneaking_speed"));
    }

    private static Attribute attribute(String... keys) {
        for (String key : keys) {
            try {
                Attribute a = (Attribute) Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
                if (a != null) {
                    return a;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    public static double value(LivingEntity e, Attribute attribute) {
        if (attribute != null && e != null) {
            try {
                AttributeInstance inst = e.getAttribute(attribute);
                double value = inst == null ? Double.NaN : inst.getValue();
                return Double.isFinite(value) ? value : Double.NaN;
            } catch (Throwable ignored) {
                return Double.NaN;
            }
        } else {
            return Double.NaN;
        }
    }
}
