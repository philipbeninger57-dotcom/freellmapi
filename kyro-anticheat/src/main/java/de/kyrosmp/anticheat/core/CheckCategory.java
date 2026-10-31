package de.kyrosmp.anticheat.core;

public enum CheckCategory {
    MOVEMENT("Movement", "FEATHER"),
    VEHICLE("Vehicles & Elytra", "OAK_BOAT"),
    COMBAT("Combat", "TARGET"),
    CRYSTAL("Crystal PvP", "END_CRYSTAL"),
    INVENTORY("Inventory & Items", "CHEST"),
    BLOCK("Blocks & Scaffold", "SCAFFOLDING"),
    PACKET("Packets & Protocol", "COMPARATOR"),
    EXPLOIT("Exploits", "TNT"),
    OTHER("Other", "PAPER");

    private final String displayName;
    private final String iconMaterial;

    private CheckCategory(String displayName, String iconMaterial) {
        this.displayName = displayName;
        this.iconMaterial = iconMaterial;
    }

    public String displayName() {
        return this.displayName;
    }

    public String iconMaterial() {
        return this.iconMaterial;
    }

    public static CheckCategory parse(String value) {
        if (value == null) {
            return OTHER;
        } else {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ignored) {
                return OTHER;
            }
        }
    }
}
