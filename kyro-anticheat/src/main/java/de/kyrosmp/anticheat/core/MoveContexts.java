package de.kyrosmp.anticheat.core;

import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class MoveContexts {
    private static volatile Object lunge;
    private static volatile boolean lungeResolved;

    private MoveContexts() {}

    public static MoveContext capture(
            Player p, Location from, Location to, long now, long nanoNow, AttributeSet attrs) {
        World w = to.getWorld();
        return MoveContext.builder()
                .time(now, nanoNow)
                .world(w)
                .from(from.getX(), from.getY(), from.getZ())
                .to(to.getX(), to.getY(), to.getZ())
                .rotation(to.getYaw(), to.getPitch())
                .gameMode(p.getGameMode())
                .allowFlight(p.getAllowFlight())
                .flying(p.isFlying())
                .gliding(p.isGliding())
                .riptiding(p.isRiptiding())
                .swimming(p.isSwimming())
                .insideVehicle(p.isInsideVehicle())
                .sprinting(p.isSprinting())
                .clientOnGround(clientOnGround(p))
                .invulnerable(p.isInvulnerable())
                .holdingMace(holdingMace(p))
                .fallDamageEnabled(fallDamageEnabled(w))
                .levitation(p.getPotionEffect(PotionEffectType.LEVITATION) != null)
                .slowFalling(p.getPotionEffect(PotionEffectType.SLOW_FALLING) != null)
                .ping(Punisher.safePing(p))
                .jumpAmplifier(amplifier(p, PotionEffectType.JUMP_BOOST))
                .speedAmplifier(amplifier(p, PotionEffectType.SPEED))
                .walkSpeed(p.getWalkSpeed())
                .movementSpeedAttr(AttributeSet.value(p, attrs.movementSpeed()))
                .jumpStrengthAttr(AttributeSet.value(p, attrs.jumpStrength()))
                .gravityAttr(effectiveGravity(p, AttributeSet.value(p, attrs.gravity())))
                .safeFallAttr(AttributeSet.value(p, attrs.safeFallDistance()))
                .fallDamageMultiplierAttr(AttributeSet.value(p, attrs.fallDamageMultiplier()))
                .stepHeightAttr(AttributeSet.value(p, attrs.stepHeight()))
                .itemUse(usingItem(p), itemUseTicks(p), itemUseRemaining(p), ItemUseSpeed.of(p))
                .lungeWeapon(lungeWeapon(p))
                .sneaking(sneaking(p))
                .sneakingSpeedAttr(AttributeSet.value(p, attrs.sneakingSpeed()))
                .foodLevel(p.getFoodLevel())
                .geometry(new WorldGeometry(w, p, from, to))
                .build();
    }

    static boolean usingItem(Player p) {
        try {
            return p.hasActiveItem();
        } catch (Throwable ex) {
            return false;
        }
    }

    static int itemUseTicks(Player p) {
        try {
            return Math.max(0, p.getActiveItemUsedTime());
        } catch (Throwable ex) {
            return 0;
        }
    }

    static int itemUseRemaining(Player p) {
        try {
            return Math.max(0, p.getActiveItemRemainingTime());
        } catch (Throwable ex) {
            return 0;
        }
    }

    static boolean sneaking(Player p) {
        try {
            return p.isSneaking();
        } catch (Throwable ex) {
            return false;
        }
    }

    static boolean lungeWeapon(Player p) {
        try {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand != null && hand.getType().name().endsWith("_SPEAR")) {
                if (!lungeResolved) {
                    lunge = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lunge"));
                    lungeResolved = true;
                }

                Object ench = lunge;
                return ench == null || hand.getEnchantmentLevel((Enchantment) ench) > 0;
            } else {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    static double effectiveGravity(Player player, double attributeGravity) {
        return player.hasGravity() ? attributeGravity : 0.0;
    }

    private static int amplifier(Player p, PotionEffectType type) {
        PotionEffect e = p.getPotionEffect(type);
        return e == null ? -1 : e.getAmplifier();
    }

    private static boolean clientOnGround(Player p) {
        return p.isOnGround();
    }

    public static boolean holdingMace(Player p) {
        try {
            return p.getInventory().getItemInMainHand().getType().name().equals("MACE");
        } catch (Throwable ex) {
            return false;
        }
    }

    public static boolean fallDamageEnabled(World w) {
        try {
            Boolean value = (Boolean) w.getGameRuleValue(GameRule.FALL_DAMAGE);
            return value == null || value;
        } catch (Throwable ex) {
            return true;
        }
    }
}
