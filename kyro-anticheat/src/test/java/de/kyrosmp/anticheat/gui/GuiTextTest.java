package de.kyrosmp.anticheat.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class GuiTextTest {
    @Test
    void rejectsWidthsThatCannotMakeProgress() {
        assertThrows(IllegalArgumentException.class, () -> GuiText.wrap("text", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> GuiText.wrap("text", -1, ""));
    }

    @Test
    void longWordsAndFollowingTextArePreserved() {
        assertEquals(
                List.of("x:abcd", "x:efgh", "x:ij", "x:next"),
                GuiText.wrap("abcdefghij next", 4, "x:"));
    }

    @Test
    void syntacticallyValidButUnformattablePatternFallsBack() {
        // pH reserves one character; a two-digit hour cannot fit.
        GuiText text = new GuiText("pH", "UTC");
        assertEquals(
                "01.01.2026 13:00:00",
                text.time(Instant.parse("2026-01-01T13:00:00Z").toEpochMilli()));
    }
}
