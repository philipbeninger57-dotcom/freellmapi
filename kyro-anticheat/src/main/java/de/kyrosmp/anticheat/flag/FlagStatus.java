/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.flag;

public enum FlagStatus {
    NEW("New", "\u00a7c"),
    REVIEWED("Reviewed", "\u00a7e"),
    RESOLVED("Resolved", "\u00a7a");

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
        }
        try {
            return FlagStatus.valueOf(value);
        }
        catch (IllegalArgumentException ex) {
            return NEW;
        }
    }
}

