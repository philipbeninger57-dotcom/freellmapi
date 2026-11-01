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
        ZoneId zoneId;
        try {
            zoneId =
                    zone != null && !zone.isBlank() && !zone.equalsIgnoreCase("system")
                            ? ZoneId.of(zone)
                            : ZoneId.systemDefault();
        } catch (RuntimeException ignored) {
            zoneId = ZoneId.systemDefault();
        }

        DateTimeFormatter f;
        try {
            f = DateTimeFormatter.ofPattern(pattern, Locale.GERMANY);
        } catch (RuntimeException ignored) {
            f = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY);
        }

        this.formatter = f.withZone(zoneId);
        this.fallback =
                DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).withZone(zoneId);
    }

    public String time(long epochMs) {
        if (epochMs <= 0L) {
            return "-";
        } else {
            Instant instant = Instant.ofEpochMilli(epochMs);

            try {
                return this.formatter.format(instant);
            } catch (RuntimeException ignored) {
                return this.fallback.format(instant);
            }
        }
    }

    public static String vl(double vl) {
        return Double.isNaN(vl) ? "-" : String.format(Locale.ROOT, "%.1f", vl);
    }

    public static String ago(long now, long then) {
        long s = Math.max(0L, (now - then) / 1000L);
        if (s < 60L) {
            return s + "s ago";
        } else if (s < 3600L) {
            return s / 60L + "m ago";
        } else {
            return s < 86400L ? s / 3600L + "h ago" : s / 86400L + "d ago";
        }
    }

    public static List<String> wrap(String text, int width, String colorPrefix) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        } else {
            ArrayList<String> out = new ArrayList<>();
            if (text != null && !text.isBlank()) {
                StringBuilder line = new StringBuilder();
                for (String token : text.trim().split("\\s+")) {
                    String word;
                    for (word = token; word.length() > width; word = word.substring(width)) {
                        if (line.length() > 0) {
                            out.add(colorPrefix + line);
                            line.setLength(0);
                        }

                        out.add(colorPrefix + word.substring(0, width));
                    }

                    if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                        out.add(colorPrefix + line);
                        line.setLength(0);
                    }

                    if (line.length() > 0) {
                        line.append(' ');
                    }

                    line.append(word);
                }

                if (line.length() > 0) {
                    out.add(colorPrefix + line);
                }

                return out;
            } else {
                return out;
            }
        }
    }

    public static List<String> details(String details, int width, String colorPrefix) {
        ArrayList<String> out = new ArrayList<>();
        if (details == null) {
            return out;
        } else {
            for (String part : details.split(";")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    out.addAll(wrap(p, width, colorPrefix));
                }
            }

            return out;
        }
    }
}
