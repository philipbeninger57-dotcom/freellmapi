/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.GameMode
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.MoveContext;
import de.kyrosmp.anticheat.core.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

public final class ExemptionManager {
    private volatile CheckConfig cfg;

    public ExemptionManager(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public boolean bypass(Player p) {
        return p.hasPermission("kyroac.bypass");
    }

    public String bypassReason(Player p) {
        return this.bypass(p) ? "Permission kyroac.bypass" : null;
    }

    public String movementReason(MoveContext m, PlayerData d) {
        CheckConfig c = this.cfg;
        long now = m.now;
        GameMode gm = m.gameMode;
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return "Gamemode " + String.valueOf(gm);
        }
        if (m.allowFlight) {
            return "Flight allowed";
        }
        if (m.flying) {
            return "Flying";
        }
        if (m.gliding) {
            return "Elytra";
        }
        if (m.insideVehicle) {
            return "Vehicle";
        }
        if (m.swimming) {
            return "Swimming";
        }
        if (m.riptiding) {
            return "Riptide";
        }
        if (now - d.lastGlide < c.graceElytraMs()) {
            return "After elytra";
        }
        if (now - d.lastRiptide < c.graceRiptideMs()) {
            return "After riptide";
        }
        if (now - d.lastSwimming < 1200L) {
            return "After swimming";
        }
        if (now - d.lastVehicle < 1500L) {
            return "After vehicle";
        }
        if (m.levitation) {
            return "Levitation";
        }
        if (m.slowFalling) {
            return "Slow Falling";
        }
        if (now - d.lastLiquid < 600L) {
            return "Liquid/climbable";
        }
        if (now - d.lastBounceSurface < 3500L && !d.lastGround) {
            return "Slime/bed/honey bounce";
        }
        if (now - d.lastTeleport < c.graceTeleportMs()) {
            return "Teleport";
        }
        if (now < d.velocityGraceUntil) {
            return "Velocity/knockback";
        }
        if (now - d.lastDamage < 1500L) {
            return "Recently damaged";
        }
        return null;
    }
}

