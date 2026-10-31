package de.kyrosmp.anticheat.core;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

public final class BedrockDetector {
    private BedrockDetector() {}

    public static boolean isBedrock(Player p, PlayerData d) {
        Boolean cached = d.bedrock;
        if (cached != null) {
            return cached;
        } else {
            Boolean api = floodgate(p.getUniqueId());
            boolean bedrock = api != null ? api : looksLikeFloodgate(p.getName(), p.getUniqueId());
            d.bedrock = bedrock;
            return bedrock;
        }
    }

    public static boolean looksLikeFloodgate(String name, UUID uuid) {
        return name != null
                && name.startsWith(".")
                && uuid != null
                && uuid.getMostSignificantBits() == 0L;
    }

    private static Boolean floodgate(UUID id) {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Method isFloodgate = api.getMethod("isFloodgatePlayer", UUID.class);
            Object object = isFloodgate.invoke(instance, id);
            Boolean b;
            return object instanceof Boolean ? (b = (Boolean) object) : Boolean.FALSE;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
