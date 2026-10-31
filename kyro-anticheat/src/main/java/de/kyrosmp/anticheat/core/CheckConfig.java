package de.kyrosmp.anticheat.core;

import org.bukkit.configuration.ConfigurationSection;

public record CheckConfig(
        boolean bedrockExempt,
        boolean flyEnabled,
        double flyFlagAt,
        boolean flySetback,
        boolean flyKick,
        int flyKickFlags,
        long flyKickWindowMs,
        int flyKickMaxPing,
        String flyKickMessage,
        double kickMinTps,
        long graceTeleportMs,
        long graceVelocityMs,
        long graceElytraMs,
        long graceRiptideMs,
        boolean speedEnabled,
        double speedFlagAt,
        boolean speedSetback,
        boolean timerEnabled,
        double timerMaxBalanceMs,
        boolean noFallEnabled,
        boolean groundSpoofSetback,
        boolean badPacketsEnabled,
        boolean boatEnabled,
        double boatFlagAt,
        boolean boatSetback,
        boolean boatKick,
        int boatKickFlags,
        long boatKickWindowMs,
        int boatKickMaxPing,
        String boatKickMessage,
        boolean jesusEnabled,
        int jesusTicks,
        double jesusFlagAt,
        boolean jesusSetback,
        boolean reachEnabled,
        boolean reachCancel,
        double reachTolerance,
        boolean killAuraEnabled,
        boolean killAuraCancel,
        double killAuraMaxAngle,
        long switchMaxIntervalMs,
        long switchWindowMs,
        int switchMaxSwitches,
        boolean velocityEnabled,
        double velocityMinKnockback,
        double velocityFlagAt,
        boolean autoClickerEnabled,
        int autoClickerMaxCps,
        int autoClickerSustainSeconds,
        boolean scaffoldEnabled,
        double scaffoldFlagAt,
        double scaffoldTolerance,
        boolean fastPlaceEnabled,
        int fastPlaceMaxPerSecond,
        double fastPlaceFlagAt,
        boolean inventoryMoveEnabled,
        double inventoryMoveMinSpeed,
        double inventoryMoveFlagAt,
        boolean elytraEnabled,
        double elytraFlagAt,
        boolean elytraSetback,
        double elytraMaxSpeed,
        double switchMinAngle,
        int elytraSetbackAfterFlags,
        long elytraSetbackWindowMs,
        boolean wallHitEnabled,
        double wallHitFlagAt,
        boolean xrayEnabled,
        int xrayMinVeins,
        double xrayMaxBlocksPerVein,
        long xrayWindowMs,
        boolean criticalsEnabled,
        double criticalsFlagAt) {
    public static final String DEFAULT_FLY_KICK =
            "&5&lKYROSMP\n"
                    + "&cUnfair Advantage Detected\n"
                    + "&7Reason: &fFly\n\n"
                    + "&8If this was a mistake, contact staff.";
    public static final String DEFAULT_BOAT_KICK =
            "&5&lKYROSMP\n"
                    + "&cUnfair Advantage Detected\n"
                    + "&7Reason: &fBoatFly\n\n"
                    + "&8If this was a mistake, contact staff.";

    public CheckConfig(
            boolean bedrockExempt,
            boolean flyEnabled,
            double flyFlagAt,
            boolean flySetback,
            boolean flyKick,
            int flyKickFlags,
            long flyKickWindowMs,
            int flyKickMaxPing,
            String flyKickMessage,
            double kickMinTps,
            long graceTeleportMs,
            long graceVelocityMs,
            long graceElytraMs,
            long graceRiptideMs,
            boolean speedEnabled,
            double speedFlagAt,
            boolean speedSetback,
            boolean timerEnabled,
            double timerMaxBalanceMs,
            boolean noFallEnabled,
            boolean groundSpoofSetback,
            boolean badPacketsEnabled,
            boolean boatEnabled,
            double boatFlagAt,
            boolean boatSetback,
            boolean boatKick,
            int boatKickFlags,
            long boatKickWindowMs,
            int boatKickMaxPing,
            String boatKickMessage,
            boolean jesusEnabled,
            int jesusTicks,
            double jesusFlagAt,
            boolean jesusSetback,
            boolean reachEnabled,
            boolean reachCancel,
            double reachTolerance,
            boolean killAuraEnabled,
            boolean killAuraCancel,
            double killAuraMaxAngle,
            long switchMaxIntervalMs,
            long switchWindowMs,
            int switchMaxSwitches,
            boolean velocityEnabled,
            double velocityMinKnockback,
            double velocityFlagAt,
            boolean autoClickerEnabled,
            int autoClickerMaxCps,
            int autoClickerSustainSeconds,
            boolean scaffoldEnabled,
            double scaffoldFlagAt,
            double scaffoldTolerance,
            boolean fastPlaceEnabled,
            int fastPlaceMaxPerSecond,
            double fastPlaceFlagAt,
            boolean inventoryMoveEnabled,
            double inventoryMoveMinSpeed,
            double inventoryMoveFlagAt,
            boolean elytraEnabled,
            double elytraFlagAt,
            boolean elytraSetback,
            double elytraMaxSpeed,
            double switchMinAngle,
            int elytraSetbackAfterFlags,
            long elytraSetbackWindowMs,
            boolean wallHitEnabled,
            double wallHitFlagAt,
            boolean xrayEnabled,
            int xrayMinVeins,
            double xrayMaxBlocksPerVein,
            long xrayWindowMs,
            boolean criticalsEnabled,
            double criticalsFlagAt) {
        flyKick = false;
        boatKick = false;
        this.bedrockExempt = bedrockExempt;
        this.flyEnabled = flyEnabled;
        this.flyFlagAt = flyFlagAt;
        this.flySetback = flySetback;
        this.flyKick = flyKick;
        this.flyKickFlags = flyKickFlags;
        this.flyKickWindowMs = flyKickWindowMs;
        this.flyKickMaxPing = flyKickMaxPing;
        this.flyKickMessage = flyKickMessage;
        this.kickMinTps = kickMinTps;
        this.graceTeleportMs = graceTeleportMs;
        this.graceVelocityMs = graceVelocityMs;
        this.graceElytraMs = graceElytraMs;
        this.graceRiptideMs = graceRiptideMs;
        this.speedEnabled = speedEnabled;
        this.speedFlagAt = speedFlagAt;
        this.speedSetback = speedSetback;
        this.timerEnabled = timerEnabled;
        this.timerMaxBalanceMs = timerMaxBalanceMs;
        this.noFallEnabled = noFallEnabled;
        this.groundSpoofSetback = groundSpoofSetback;
        this.badPacketsEnabled = badPacketsEnabled;
        this.boatEnabled = boatEnabled;
        this.boatFlagAt = boatFlagAt;
        this.boatSetback = boatSetback;
        this.boatKick = boatKick;
        this.boatKickFlags = boatKickFlags;
        this.boatKickWindowMs = boatKickWindowMs;
        this.boatKickMaxPing = boatKickMaxPing;
        this.boatKickMessage = boatKickMessage;
        this.jesusEnabled = jesusEnabled;
        this.jesusTicks = jesusTicks;
        this.jesusFlagAt = jesusFlagAt;
        this.jesusSetback = jesusSetback;
        this.reachEnabled = reachEnabled;
        this.reachCancel = reachCancel;
        this.reachTolerance = reachTolerance;
        this.killAuraEnabled = killAuraEnabled;
        this.killAuraCancel = killAuraCancel;
        this.killAuraMaxAngle = killAuraMaxAngle;
        this.switchMaxIntervalMs = switchMaxIntervalMs;
        this.switchWindowMs = switchWindowMs;
        this.switchMaxSwitches = switchMaxSwitches;
        this.velocityEnabled = velocityEnabled;
        this.velocityMinKnockback = velocityMinKnockback;
        this.velocityFlagAt = velocityFlagAt;
        this.autoClickerEnabled = autoClickerEnabled;
        this.autoClickerMaxCps = autoClickerMaxCps;
        this.autoClickerSustainSeconds = autoClickerSustainSeconds;
        this.scaffoldEnabled = scaffoldEnabled;
        this.scaffoldFlagAt = scaffoldFlagAt;
        this.scaffoldTolerance = scaffoldTolerance;
        this.fastPlaceEnabled = fastPlaceEnabled;
        this.fastPlaceMaxPerSecond = fastPlaceMaxPerSecond;
        this.fastPlaceFlagAt = fastPlaceFlagAt;
        this.inventoryMoveEnabled = inventoryMoveEnabled;
        this.inventoryMoveMinSpeed = inventoryMoveMinSpeed;
        this.inventoryMoveFlagAt = inventoryMoveFlagAt;
        this.elytraEnabled = elytraEnabled;
        this.elytraFlagAt = elytraFlagAt;
        this.elytraSetback = elytraSetback;
        this.elytraMaxSpeed = elytraMaxSpeed;
        this.switchMinAngle = switchMinAngle;
        this.elytraSetbackAfterFlags = elytraSetbackAfterFlags;
        this.elytraSetbackWindowMs = elytraSetbackWindowMs;
        this.wallHitEnabled = wallHitEnabled;
        this.wallHitFlagAt = wallHitFlagAt;
        this.xrayEnabled = xrayEnabled;
        this.xrayMinVeins = xrayMinVeins;
        this.xrayMaxBlocksPerVein = xrayMaxBlocksPerVein;
        this.xrayWindowMs = xrayWindowMs;
        this.criticalsEnabled = criticalsEnabled;
        this.criticalsFlagAt = criticalsFlagAt;
    }

    public static CheckConfig defaults() {
        return new CheckConfig(
                true,
                true,
                5.0,
                true,
                true,
                4,
                30000L,
                180,
                "&5&lKYROSMP\n"
                        + "&cUnfair Advantage Detected\n"
                        + "&7Reason: &fFly\n\n"
                        + "&8If this was a mistake, contact staff.",
                18.0,
                3000L,
                2500L,
                3000L,
                2500L,
                true,
                6.0,
                true,
                true,
                350.0,
                true,
                true,
                true,
                true,
                4.5,
                true,
                true,
                4,
                30000L,
                180,
                "&5&lKYROSMP\n"
                        + "&cUnfair Advantage Detected\n"
                        + "&7Reason: &fBoatFly\n\n"
                        + "&8If this was a mistake, contact staff.",
                true,
                8,
                3.0,
                false,
                true,
                true,
                0.3,
                true,
                true,
                75.0,
                140L,
                800L,
                6,
                true,
                0.3,
                3.0,
                true,
                30,
                3,
                true,
                4.0,
                0.25,
                true,
                20,
                3.0,
                true,
                0.15,
                4.0,
                true,
                4.0,
                true,
                4.5,
                30.0,
                2,
                10000L,
                true,
                4.0,
                true,
                5,
                40.0,
                1200000L,
                true,
                4.0);
    }

    public static CheckConfig from(ConfigurationSection c) {
        CheckConfig d = defaults();
        return new CheckConfig(
                c.getBoolean("bedrock.exempt", d.bedrockExempt),
                c.getBoolean("checks.fly.enabled", d.flyEnabled),
                c.getDouble("checks.fly.buffer.flag-at", d.flyFlagAt),
                c.getBoolean("checks.fly.punishment.setback", d.flySetback),
                c.getBoolean("checks.fly.punishment.kick", d.flyKick),
                c.getInt("checks.fly.punishment.flags", d.flyKickFlags),
                c.getLong("checks.fly.punishment.window-seconds", d.flyKickWindowMs / 1000L)
                        * 1000L,
                c.getInt("checks.fly.punishment.max-ping-for-kick", d.flyKickMaxPing),
                c.getString("checks.fly.punishment.kick-message", d.flyKickMessage),
                c.getDouble("punishments.kick-min-tps", d.kickMinTps),
                c.getLong("checks.fly.grace.teleport-ms", d.graceTeleportMs),
                c.getLong("checks.fly.grace.velocity-ms", d.graceVelocityMs),
                c.getLong("checks.fly.grace.elytra-ms", d.graceElytraMs),
                c.getLong("checks.fly.grace.riptide-ms", d.graceRiptideMs),
                c.getBoolean("checks.speed.enabled", d.speedEnabled),
                c.getDouble("checks.speed.buffer.flag-at", d.speedFlagAt),
                c.getBoolean("checks.speed.setback", d.speedSetback),
                c.getBoolean("checks.timer.enabled", d.timerEnabled),
                c.getDouble("checks.timer.max-balance-ms", d.timerMaxBalanceMs),
                c.getBoolean("checks.nofall.enabled", d.noFallEnabled),
                c.getBoolean("checks.nofall.ground-spoof-setback", d.groundSpoofSetback),
                c.getBoolean("checks.badpackets.enabled", d.badPacketsEnabled),
                c.getBoolean("checks.boatfly.enabled", d.boatEnabled),
                c.getDouble("checks.boatfly.buffer.flag-at", d.boatFlagAt),
                c.getBoolean("checks.boatfly.punishment.setback", d.boatSetback),
                c.getBoolean("checks.boatfly.punishment.kick", d.boatKick),
                c.getInt("checks.boatfly.punishment.flags", d.boatKickFlags),
                c.getLong("checks.boatfly.punishment.window-seconds", d.boatKickWindowMs / 1000L)
                        * 1000L,
                c.getInt("checks.boatfly.punishment.max-ping-for-kick", d.boatKickMaxPing),
                c.getString("checks.boatfly.punishment.kick-message", d.boatKickMessage),
                c.getBoolean("checks.jesus.enabled", d.jesusEnabled),
                c.getInt("checks.jesus.ticks", d.jesusTicks),
                c.getDouble("checks.jesus.buffer.flag-at", d.jesusFlagAt),
                c.getBoolean("checks.jesus.setback", d.jesusSetback),
                c.getBoolean("checks.reach.enabled", d.reachEnabled),
                c.getBoolean("checks.reach.cancel-hit", d.reachCancel),
                c.getDouble("checks.reach.tolerance", d.reachTolerance),
                c.getBoolean("checks.killaura.enabled", d.killAuraEnabled),
                c.getBoolean("checks.killaura.cancel-hit", d.killAuraCancel),
                c.getDouble("checks.killaura.max-angle", d.killAuraMaxAngle),
                c.getLong("checks.killaura.switch.max-interval-ms", d.switchMaxIntervalMs),
                c.getLong("checks.killaura.switch.window-ms", d.switchWindowMs),
                c.getInt("checks.killaura.switch.max-switches", d.switchMaxSwitches),
                c.getBoolean("checks.velocity.enabled", d.velocityEnabled),
                c.getDouble("checks.velocity.min-knockback", d.velocityMinKnockback),
                c.getDouble("checks.velocity.buffer.flag-at", d.velocityFlagAt),
                c.getBoolean("checks.autoclicker.enabled", d.autoClickerEnabled),
                c.getInt("checks.autoclicker.max-cps", d.autoClickerMaxCps),
                c.getInt("checks.autoclicker.sustain-seconds", d.autoClickerSustainSeconds),
                c.getBoolean("checks.scaffold.enabled", d.scaffoldEnabled),
                c.getDouble("checks.scaffold.buffer.flag-at", d.scaffoldFlagAt),
                c.getDouble("checks.scaffold.tolerance", d.scaffoldTolerance),
                c.getBoolean("checks.fastplace.enabled", d.fastPlaceEnabled),
                c.getInt("checks.fastplace.max-per-second", d.fastPlaceMaxPerSecond),
                c.getDouble("checks.fastplace.buffer.flag-at", d.fastPlaceFlagAt),
                c.getBoolean("checks.inventorymove.enabled", d.inventoryMoveEnabled),
                c.getDouble("checks.inventorymove.min-speed", d.inventoryMoveMinSpeed),
                c.getDouble("checks.inventorymove.buffer.flag-at", d.inventoryMoveFlagAt),
                c.getBoolean("checks.elytrafly.enabled", d.elytraEnabled),
                c.getDouble("checks.elytrafly.buffer.flag-at", d.elytraFlagAt),
                c.getBoolean("checks.elytrafly.setback", d.elytraSetback),
                c.getDouble("checks.elytrafly.max-speed", d.elytraMaxSpeed),
                c.getDouble("checks.killaura.switch.min-angle", d.switchMinAngle),
                c.getInt("checks.elytrafly.setback-after-flags", d.elytraSetbackAfterFlags),
                c.getLong(
                                "checks.elytrafly.setback-window-seconds",
                                d.elytraSetbackWindowMs / 1000L)
                        * 1000L,
                c.getBoolean("checks.killaura.wall.enabled", d.wallHitEnabled),
                c.getDouble("checks.killaura.wall.buffer.flag-at", d.wallHitFlagAt),
                c.getBoolean("checks.xray.enabled", d.xrayEnabled),
                c.getInt("checks.xray.min-hidden-veins", d.xrayMinVeins),
                c.getDouble("checks.xray.max-blocks-per-vein", d.xrayMaxBlocksPerVein),
                c.getLong("checks.xray.window-minutes", d.xrayWindowMs / 60000L) * 60000L,
                c.getBoolean("checks.criticals.enabled", d.criticalsEnabled),
                c.getDouble("checks.criticals.buffer.flag-at", d.criticalsFlagAt));
    }
}
