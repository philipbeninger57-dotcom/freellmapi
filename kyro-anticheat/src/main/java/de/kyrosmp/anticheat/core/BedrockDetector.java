/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.PlayerData;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.entity.Player;

public final class BedrockDetector {
    private BedrockDetector() {
    }

    public static boolean isBedrock(Player p, PlayerData d) {
        Boolean cached = d.bedrock;
        if (cached != null) {
            return cached;
        }
        Boolean api = BedrockDetector.floodgate(p.getUniqueId());
        boolean bedrock = api != null ? api : BedrockDetector.looksLikeFloodgate(p.getName(), p.getUniqueId());
        d.bedrock = bedrock;
        return bedrock;
    }

    public static boolean looksLikeFloodgate(String name, UUID uuid) {
        return name != null && name.startsWith(".") && uuid != null && uuid.getMostSignificantBits() == 0L;
    }

    private static Boolean floodgate(UUID id) {
        try {
            Boolean b;
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance", new Class[0]).invoke(null, new Object[0]);
            Method isFloodgate = api.getMethod("isFloodgatePlayer", UUID.class);
            Object object = isFloodgate.invoke(instance, id);
            return object instanceof Boolean ? (b = (Boolean)object) : Boolean.FALSE;
        }
        catch (Throwable ignored) {
            return null;
        }
    }
}

