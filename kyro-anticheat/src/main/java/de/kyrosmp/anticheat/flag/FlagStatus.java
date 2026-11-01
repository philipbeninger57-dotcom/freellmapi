package de.kyrosmp.anticheat.flag;

public enum FlagStatus {
    NEW("New", "§c"),
    REVIEWED("Reviewed", "§e"),
    RESOLVED("Resolved", "§a");

    private final String label;
    private final String color;

    private FlagStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return this.label;
    }

    public String colored() {
        return this.color + this.label;
    }

    public static FlagStatus parse(String value) {
        if (value == null) {
            return NEW;
        } else {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ignored) {
                return NEW;
            }
        }
    }
}
