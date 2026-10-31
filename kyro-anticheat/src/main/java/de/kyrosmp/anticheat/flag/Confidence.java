/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.flag;

public enum Confidence {
    LOW("Low", "\u00a7a"),
    MEDIUM("Medium", "\u00a7e"),
    HIGH("High", "\u00a7c");

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
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public static Confidence parse(String value) {
        if (value == null) {
            return LOW;
        }
        try {
            return Confidence.valueOf(value);
        }
        catch (IllegalArgumentException ex) {
            return LOW;
        }
    }
}

