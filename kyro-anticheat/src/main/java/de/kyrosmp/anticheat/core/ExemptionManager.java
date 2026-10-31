package de.kyrosmp.anticheat.core;

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
            return "Gamemode " + gm;
        } else if (m.allowFlight) {
            return "Flight allowed";
        } else if (m.flying) {
            return "Flying";
        } else if (m.gliding) {
            return "Elytra";
        } else if (m.insideVehicle) {
            return "Vehicle";
        } else if (m.swimming) {
            return "Swimming";
        } else if (m.riptiding) {
            return "Riptide";
        } else if (now - d.lastGlide < c.graceElytraMs()) {
            return "After elytra";
        } else if (now - d.lastRiptide < c.graceRiptideMs()) {
            return "After riptide";
        } else if (now - d.lastSwimming < 1200L) {
            return "After swimming";
        } else if (now - d.lastVehicle < 1500L) {
            return "After vehicle";
        } else if (m.levitation) {
            return "Levitation";
        } else if (m.slowFalling) {
            return "Slow Falling";
        } else if (now - d.lastLiquid < 600L) {
            return "Liquid/climbable";
        } else if (now - d.lastBounceSurface < 3500L && !d.lastGround) {
            return "Slime/bed/honey bounce";
        } else if (now - d.lastTeleport < c.graceTeleportMs()) {
            return "Teleport";
        } else if (now < d.velocityGraceUntil) {
            return "Velocity/knockback";
        } else {
            return now - d.lastDamage < 1500L ? "Recently damaged" : null;
        }
    }
}
