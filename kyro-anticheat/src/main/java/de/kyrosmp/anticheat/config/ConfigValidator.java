/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.configuration.Configuration
 *  org.bukkit.configuration.file.FileConfiguration
 */
package de.kyrosmp.anticheat.config;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigValidator {
    private static final List<NumberRule> NUMBERS = List.of(new NumberRule("checks.fly.buffer.flag-at", 0.5, 100.0), new NumberRule("checks.fly.grace.teleport-ms", 0.0, 30000.0), new NumberRule("checks.fly.grace.velocity-ms", 0.0, 30000.0), new NumberRule("checks.fly.grace.elytra-ms", 0.0, 30000.0), new NumberRule("checks.fly.grace.riptide-ms", 0.0, 30000.0), new NumberRule("checks.fly.punishment.flags", 1.0, 1000.0), new NumberRule("checks.fly.punishment.window-seconds", 1.0, 3600.0), new NumberRule("checks.fly.punishment.max-ping-for-kick", 0.0, 10000.0), new NumberRule("punishments.kick-min-tps", 0.0, 20.0), new NumberRule("checks.speed.buffer.flag-at", 0.5, 100.0), new NumberRule("checks.timer.max-balance-ms", 50.0, 5000.0), new NumberRule("checks.boatfly.buffer.flag-at", 0.5, 100.0), new NumberRule("checks.boatfly.punishment.flags", 1.0, 1000.0), new NumberRule("checks.boatfly.punishment.window-seconds", 1.0, 3600.0), new NumberRule("checks.boatfly.punishment.max-ping-for-kick", 0.0, 10000.0), new NumberRule("checks.reach.tolerance", 0.0, 3.0), new NumberRule("checks.killaura.max-angle", 30.0, 180.0), new NumberRule("checks.killaura.switch.max-interval-ms", 10.0, 2000.0), new NumberRule("checks.killaura.switch.window-ms", 100.0, 10000.0), new NumberRule("checks.killaura.switch.max-switches", 2.0, 100.0), new NumberRule("checks.killaura.switch.min-angle", 0.0, 180.0), new NumberRule("checks.elytrafly.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.elytrafly.max-speed", 3.0, 20.0), new NumberRule("checks.elytrafly.setback-after-flags", 1.0, 100.0), new NumberRule("checks.elytrafly.setback-window-seconds", 1.0, 600.0), new NumberRule("checks.killaura.wall.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.xray.min-hidden-veins", 2.0, 1000.0), new NumberRule("checks.xray.max-blocks-per-vein", 1.0, 10000.0), new NumberRule("checks.xray.window-minutes", 1.0, 1440.0), new NumberRule("checks.criticals.buffer.flag-at", 1.0, 100.0), new NumberRule("gui.inspection.max-distance", 16.0, 10000.0), new NumberRule("gui.inspection.check-interval-ticks", 5.0, 1200.0), new NumberRule("checks.jesus.ticks", 2.0, 100.0), new NumberRule("checks.jesus.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.velocity.min-knockback", 0.05, 5.0), new NumberRule("checks.velocity.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.autoclicker.max-cps", 10.0, 200.0), new NumberRule("checks.autoclicker.sustain-seconds", 1.0, 60.0), new NumberRule("checks.scaffold.tolerance", 0.0, 2.0), new NumberRule("checks.scaffold.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.fastplace.max-per-second", 5.0, 200.0), new NumberRule("checks.fastplace.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.inventorymove.min-speed", 0.01, 2.0), new NumberRule("checks.inventorymove.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.inventorymove.open-grace-ms", 0.0, 1000.0), new NumberRule("storage.retention-days", 0.0, 36500.0), new NumberRule("confidence.poor-data-min-tps", 0.0, 20.0), new NumberRule("storage.queue-capacity", 100.0, 1000000.0), new NumberRule("storage.shutdown-timeout-seconds", 1.0, 120.0), new NumberRule("flags.duplicate-window-ms", 0.0, 1000.0), new NumberRule("flags.merge-window-ms", 0.0, 60000.0), new NumberRule("flags.current-window-minutes", 1.0, 10080.0), new NumberRule("violations.vl-per-flag", 0.01, 100.0), new NumberRule("violations.decay-per-minute", 0.0, 1000.0), new NumberRule("confidence.window-minutes", 1.0, 1440.0), new NumberRule("confidence.medium-repetitions", 1.0, 1000.0), new NumberRule("confidence.high-repetitions", 1.0, 1000.0), new NumberRule("confidence.high-total-with-three-groups", 3.0, 1000.0), new NumberRule("confidence.poor-data-ping", 0.0, 10000.0), new NumberRule("debug.max-messages-per-second", 1.0, 20.0), new NumberRule("checks.fly.prediction.tolerance", 0.005, 0.5), new NumberRule("checks.fly.prediction.flag-at", 1.0, 100.0), new NumberRule("checks.fly.hover.still-ms", 600.0, 10000.0), new NumberRule("checks.step.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.speed.prediction.tolerance", 0.01, 1.0), new NumberRule("checks.speed.prediction.flag-at", 1.0, 100.0), new NumberRule("checks.noslow.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.nuker.max-per-second", 5.0, 200.0), new NumberRule("checks.nuker.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.ghosthand.buffer.flag-at", 1.0, 100.0), new NumberRule("checks.scaffold.face.buffer.flag-at", 1.0, 100.0));
    private static final List<String> BOOLEANS = List.of("bedrock.exempt", "alerts.console", "checks.fly.enabled", "checks.fly.punishment.setback", "checks.fly.punishment.kick", "checks.speed.enabled", "checks.speed.setback", "checks.timer.enabled", "checks.nofall.enabled", "checks.nofall.ground-spoof-setback", "checks.badpackets.enabled", "checks.boatfly.enabled", "checks.boatfly.punishment.setback", "checks.boatfly.punishment.kick", "checks.reach.enabled", "checks.reach.cancel-hit", "checks.killaura.enabled", "checks.killaura.cancel-hit", "checks.jesus.enabled", "checks.jesus.setback", "checks.velocity.enabled", "checks.autoclicker.enabled", "checks.scaffold.enabled", "checks.fastplace.enabled", "checks.inventorymove.enabled", "checks.elytrafly.enabled", "checks.elytrafly.setback", "checks.killaura.wall.enabled", "checks.xray.enabled", "checks.criticals.enabled", "gui.inspection.enabled", "checks.fly.prediction.enabled", "checks.fly.hover.enabled", "checks.step.enabled", "checks.speed.prediction.enabled", "checks.noslow.enabled", "checks.nuker.enabled", "checks.ghosthand.enabled", "checks.scaffold.face.enabled", "checks.criticals.prevent", "checks.reach.instant-cancel", "checks.autoclicker.cancel-hits", "checks.inventorymove.cancel-clicks", "prevention.protect-setbacks", "prevention.ground-fallback", "checks.timer.prevent");
    public static final List<String> OBSOLETE = List.of("checks.fly.buffer.decay", "checks.boatfly.buffer.decay", "checks.boatfly.buffer.minimum-samples", "checks.boatfly.lag.max-ping", "checks.boatfly.lag.max-sample-gap-ms", "checks.reach.max-distance", "checks.timer.window-ms", "checks.timer.max-rate");

    private ConfigValidator() {
    }

    public static List<String> validate(FileConfiguration config) {
        String file;
        ArrayList<String> messages = new ArrayList<String>();
        Configuration defaults = config.getDefaults();
        if (config.getBoolean("prevention.protect-setbacks", false)) {
            messages.add("prevention.protect-setbacks=true: KYRO setbacks override other plugins' teleport cancellations (strict mode); set false to respect region/combat restrictions.");
        }
        for (String key : List.of("punishments.auto-kick", "punishments.auto-ban",
                "checks.fly.punishment.kick", "checks.boatfly.punishment.kick")) {
            if (config.getBoolean(key, false)) messages.add(key + " is disabled: 3.6 has no automatic kick/ban implementation.");
            config.set(key, false);
        }
        for (String key : List.of("prevention.enabled", "prevention.instant-setback",
                "prevention.safe-position-history", "prevention.prevent-illegal-movement", "prevention.prevent-illegal-actions")) {
            if (config.isSet(key) && !(config.get(key) instanceof Boolean)) {
                config.set(key, true);
                messages.add(key + " must be a boolean; using true.");
            }
        }
        for (NumberRule rule : NUMBERS) {
            Object def;
            Object raw = config.get(rule.path());
            Object object = def = defaults == null ? null : defaults.get(rule.path());
            if (!(raw instanceof Number)) {
                if (!config.isSet(rule.path())) continue;
                messages.add(rule.path() + " is not a number ('" + String.valueOf(raw) + "') \u2013 using default " + String.valueOf(def) + ".");
                config.set(rule.path(), def);
                continue;
            }
            Number n = (Number)raw;
            double v = n.doubleValue();
            if (!Double.isNaN(v) && !(v < rule.min()) && !(v > rule.max())) continue;
            messages.add(rule.path() + " = " + v + " is outside [" + ConfigValidator.fmt(rule.min()) + ", " + ConfigValidator.fmt(rule.max()) + "] \u2013 using default " + String.valueOf(def) + ".");
            config.set(rule.path(), def);
        }
        for (String path : BOOLEANS) {
            if (!config.isSet(path) || config.get(path) instanceof Boolean) continue;
            Object def = defaults == null ? null : defaults.get(path);
            messages.add(path + " must be true or false \u2013 using default " + String.valueOf(def) + ".");
            config.set(path, def);
        }
        int medium = config.getInt("confidence.medium-repetitions", 3);
        int high = config.getInt("confidence.high-repetitions", 8);
        if (high < medium) {
            messages.add("confidence.high-repetitions (" + high + ") is lower than medium-repetitions (" + medium + ") \u2013 using " + medium + ".");
            config.set("confidence.high-repetitions", (Object)medium);
        }
        if ((file = config.getString("storage.file", "flags.db")) == null || file.isBlank() || file.contains("..") || file.contains("/") || file.contains("\\")) {
            messages.add("storage.file must be a plain file name \u2013 using 'flags.db'.");
            config.set("storage.file", (Object)"flags.db");
        }
        return messages;
    }

    public static List<String> obsoleteKeys(FileConfiguration config) {
        ArrayList<String> out = new ArrayList<String>();
        for (String key : OBSOLETE) {
            if (!config.isSet(key)) continue;
            out.add(key);
        }
        return out;
    }

    public static List<String> missingKeys(FileConfiguration config) {
        ArrayList<String> missing = new ArrayList<String>();
        Configuration defaults = config.getDefaults();
        if (defaults == null) {
            return missing;
        }
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || config.isSet(key)) continue;
            missing.add(key);
        }
        return missing;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long)d) : Double.toString(d);
    }

    private record NumberRule(String path, double min, double max) {
    }
}

