/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.gui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GuiText {
    private final DateTimeFormatter formatter;
    private final DateTimeFormatter fallback;

    public GuiText(String pattern, String zone) {
        DateTimeFormatter f;
        ZoneId zoneId;
        try {
            zoneId = zone == null || zone.isBlank() || zone.equalsIgnoreCase("system") ? ZoneId.systemDefault() : ZoneId.of(zone);
        }
        catch (RuntimeException ex) {
            zoneId = ZoneId.systemDefault();
        }
        try {
            f = DateTimeFormatter.ofPattern(pattern, Locale.GERMANY);
        }
        catch (RuntimeException ex) {
            f = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY);
        }
        this.formatter = f.withZone(zoneId);
        this.fallback = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).withZone(zoneId);
    }

    public String time(long epochMs) {
        if (epochMs <= 0L) {
            return "-";
        }
        Instant instant = Instant.ofEpochMilli(epochMs);
        try {
            return this.formatter.format(instant);
        } catch (RuntimeException ex) {
            return this.fallback.format(instant);
        }
    }

    public static String vl(double vl) {
        return Double.isNaN(vl) ? "-" : String.format(Locale.ROOT, "%.1f", vl);
    }

    public static String ago(long now, long then) {
        long s = Math.max(0L, (now - then) / 1000L);
        if (s < 60L) {
            return s + "s ago";
        }
        if (s < 3600L) {
            return s / 60L + "m ago";
        }
        if (s < 86400L) {
            return s / 3600L + "h ago";
        }
        return s / 86400L + "d ago";
    }

    public static List<String> wrap(String text, int width, String colorPrefix) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        ArrayList<String> out = new ArrayList<String>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            while (word.length() > width) {
                if (line.length() > 0) {
                    out.add(colorPrefix + String.valueOf(line));
                    line.setLength(0);
                }
                out.add(colorPrefix + word.substring(0, width));
                word = word.substring(width);
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(colorPrefix + String.valueOf(line));
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(colorPrefix + String.valueOf(line));
        }
        return out;
    }

    public static List<String> details(String details, int width, String colorPrefix) {
        ArrayList<String> out = new ArrayList<String>();
        if (details == null) {
            return out;
        }
        for (String part : details.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            out.addAll(GuiText.wrap(p, width, colorPrefix));
        }
        return out;
    }
}

