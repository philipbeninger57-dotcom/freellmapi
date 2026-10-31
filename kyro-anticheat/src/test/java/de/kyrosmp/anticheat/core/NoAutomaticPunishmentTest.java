package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.config.ConfigValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoAutomaticPunishmentTest {
    @Test void legacyAndNewKickOptionsCannotEnableAutomaticPunishments() {
        YamlConfiguration c=new YamlConfiguration();
        for(String key:new String[]{"punishments.auto-kick","punishments.auto-ban","checks.fly.punishment.kick","checks.boatfly.punishment.kick"}) c.set(key,true);
        ConfigValidator.validate(c);
        assertFalse(c.getBoolean("punishments.auto-kick"));
        assertFalse(c.getBoolean("punishments.auto-ban"));
        assertFalse(CheckConfig.from(c).flyKick());
        assertFalse(CheckConfig.from(c).boatKick());
        assertFalse(CheckConfig.defaults().flyKick());
    }
    @Test void compiledProductionClassesContainNoKickBanCommandOrLoaderApi() throws Exception {
        try(var files=Files.walk(Path.of("target/classes/de/kyrosmp/anticheat"))) {
            for(Path p:files.filter(f->f.toString().endsWith(".class")).toList()) {
                String constants=new String(Files.readAllBytes(p),StandardCharsets.ISO_8859_1);
                for(String forbidden:new String[]{"kickPlayer","banPlayer","dispatchCommand","java/net/", "javax/crypto/", "loadPermissionNodes", "SolarLoader", "defineClass"})
                    assertFalse(constants.contains(forbidden),p+" contains "+forbidden);
            }
        }
    }
    @Test void oldConfigurationGetsWorkingPreventionDefaults() {
        PreventionConfig p=PreventionConfig.from(new YamlConfiguration());
        assertTrue(p.instant()); assertTrue(p.actions()); assertTrue(p.safePositionHistory());
    }
}
