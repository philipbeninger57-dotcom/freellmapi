package de.kyrosmp.anticheat.flag;

public enum Confidence {
    LOW("Low", "§a"),
    MEDIUM("Medium", "§e"),
    HIGH("High", "§c");

    private final String label;
    private final String color;

    private Confidence(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return this.label;
    }

    public String colored() {
        return this.color + this.label;
    }

    public Confidence downgrade() {
        return this == HIGH ? MEDIUM : LOW;
    }

    public static Confidence max(Confidence a, Confidence b) {
        if (a == null) {
            return b;
        } else if (b == null) {
            return a;
        } else {
            return a.ordinal() >= b.ordinal() ? a : b;
        }
    }

    public static Confidence parse(String value) {
        if (value == null) {
            return LOW;
        } else {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ignored) {
                return LOW;
            }
        }
    }
}
