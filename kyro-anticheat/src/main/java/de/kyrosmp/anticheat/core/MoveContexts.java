/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.GameRule
 *  org.bukkit.Location
 *  org.bukkit.World
 *  org.bukkit.entity.LivingEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.potion.PotionEffect
 *  org.bukkit.potion.PotionEffectType
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.AttributeSet;
import de.kyrosmp.anticheat.core.MoveContext;
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.core.WorldGeometry;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class MoveContexts {
    private MoveContexts() {
    }

    public static MoveContext capture(Player p, Location from, Location to, long now, long nanoNow, AttributeSet attrs) {
        World w = to.getWorld();
        return MoveContext.builder().time(now, nanoNow).world(w).from(from.getX(), from.getY(), from.getZ()).to(to.getX(), to.getY(), to.getZ()).rotation(to.getYaw(), to.getPitch()).gameMode(p.getGameMode()).allowFlight(p.getAllowFlight()).flying(p.isFlying()).gliding(p.isGliding()).riptiding(p.isRiptiding()).swimming(p.isSwimming()).insideVehicle(p.isInsideVehicle()).sprinting(p.isSprinting()).clientOnGround(MoveContexts.clientOnGround(p)).invulnerable(p.isInvulnerable()).holdingMace(MoveContexts.holdingMace(p)).fallDamageEnabled(MoveContexts.fallDamageEnabled(w)).levitation(p.getPotionEffect(PotionEffectType.LEVITATION) != null).slowFalling(p.getPotionEffect(PotionEffectType.SLOW_FALLING) != null).ping(Punisher.safePing(p)).jumpAmplifier(MoveContexts.amplifier(p, PotionEffectType.JUMP_BOOST)).speedAmplifier(MoveContexts.amplifier(p, PotionEffectType.SPEED)).walkSpeed(p.getWalkSpeed()).movementSpeedAttr(AttributeSet.value((LivingEntity)p, attrs.movementSpeed())).jumpStrengthAttr(AttributeSet.value((LivingEntity)p, attrs.jumpStrength())).gravityAttr(MoveContexts.effectiveGravity(p, AttributeSet.value((LivingEntity)p, attrs.gravity()))).safeFallAttr(AttributeSet.value((LivingEntity)p, attrs.safeFallDistance())).fallDamageMultiplierAttr(AttributeSet.value((LivingEntity)p, attrs.fallDamageMultiplier())).stepHeightAttr(AttributeSet.value((LivingEntity)p, attrs.stepHeight())).itemUse(MoveContexts.usingItem(p), MoveContexts.itemUseTicks(p), MoveContexts.itemUseRemaining(p), ItemUseSpeed.of(p)).lungeWeapon(MoveContexts.lungeWeapon(p)).sneaking(MoveContexts.sneaking(p)).geometry(new WorldGeometry(w, p, from, to)).build();
    }

    static boolean usingItem(Player p) {
        try {
            return p.hasActiveItem();
        }
        catch (Throwable ex) {
            return false;
        }
    }

    static int itemUseTicks(Player p) {
        try {
            return Math.max(0, p.getActiveItemUsedTime());
        }
        catch (Throwable ex) {
            return 0;
        }
    }

    static int itemUseRemaining(Player p) {
        try {
            return Math.max(0, p.getActiveItemRemainingTime());
        }
        catch (Throwable ex) {
            return 0;
        }
    }

    static boolean sneaking(Player p) {
        try {
            return p.isSneaking();
        }
        catch (Throwable ex) {
            return false;
        }
    }

    private static volatile Object lunge;
    private static volatile boolean lungeResolved;

    /** Spears with Lunge propel the player when jabbing (1.21.11); resolved by key, never by constant. */
    static boolean lungeWeapon(Player p) {
        try {
            org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand == null || !hand.getType().name().endsWith("_SPEAR")) return false;
            if (!lungeResolved) {
                lunge = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("lunge"));
                lungeResolved = true;
            }
            Object ench = lunge;
            // Unknown enchantment registry: treat any spear as able to lunge.
            return ench == null || hand.getEnchantmentLevel((org.bukkit.enchantments.Enchantment)ench) > 0;
        }
        catch (Throwable ex) {
            return false;
        }
    }

    // Entity#setGravity(false) is a server-authorized state independent of the
    // gravity attribute (for example a plugin freezing a player in mid-air).
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
        }
        catch (Throwable ex) {
            return false;
        }
    }

    public static boolean fallDamageEnabled(World w) {
        try {
            Boolean value = (Boolean)w.getGameRuleValue(GameRule.FALL_DAMAGE);
            return value == null || value != false;
        }
        catch (Throwable ex) {
            return true;
        }
    }
}

