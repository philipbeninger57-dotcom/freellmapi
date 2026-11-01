package de.kyrosmp.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

class CheckDiagnosticsTest {
    private final AtomicLong now = new AtomicLong();
    private final List<LogRecord> records = new ArrayList<>();
    private final CheckDiagnostics diagnostics = diagnostics();

    private CheckDiagnostics diagnostics() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        records.add(record);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                });
        return new CheckDiagnostics(logger, now::get);
    }

    @Test
    void firstFailureLogsStackTraceAndRepeatedFailuresAreSuppressedForOneMinute() {
        var first = new IllegalStateException("API failure");
        diagnostics.record(CheckDiagnostics.Context.WALL_HIT, first);
        assertEquals(1, records.size());
        assertSame(first, records.getFirst().getThrown());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
        assertTrue(records.getFirst().getMessage().contains("KillAura wall visibility"));

        diagnostics.record(CheckDiagnostics.Context.WALL_HIT, new IllegalStateException("repeat"));
        now.set(59_999);
        diagnostics.record(CheckDiagnostics.Context.WALL_HIT, new IllegalStateException("repeat"));
        assertEquals(1, records.size());

        now.set(60_000);
        diagnostics.record(CheckDiagnostics.Context.WALL_HIT, new NoSuchMethodError("changed API"));
        assertEquals(2, records.size());
        assertTrue(records.get(1).getMessage().contains("suppressed_since_last_log=2"));
        assertInstanceOf(NoSuchMethodError.class, records.get(1).getThrown());
    }

    @Test
    void contextsAreIndependentAndSuppressedCountResetsAfterReporting() {
        diagnostics.record(CheckDiagnostics.Context.COMBAT_ATTACK, new IllegalArgumentException());
        diagnostics.record(CheckDiagnostics.Context.GHOST_HAND, new IllegalArgumentException());
        diagnostics.record(CheckDiagnostics.Context.COMBAT_ATTACK, new IllegalArgumentException());
        assertEquals(2, records.size());
        now.set(60_000);
        diagnostics.record(CheckDiagnostics.Context.COMBAT_ATTACK, new IllegalArgumentException());
        assertTrue(records.getLast().getMessage().contains("suppressed_since_last_log=1"));
        now.set(120_000);
        diagnostics.record(CheckDiagnostics.Context.COMBAT_ATTACK, new IllegalArgumentException());
        assertTrue(records.getLast().getMessage().contains("suppressed_since_last_log=0"));
    }

    @Test
    void fatalErrorsAreRethrownEvenInsideAnActiveSuppressionWindow() {
        diagnostics.record(CheckDiagnostics.Context.WALL_HIT, new IllegalStateException());
        var fatal = new OutOfMemoryError("test only");
        assertSame(
                fatal,
                assertThrows(
                        OutOfMemoryError.class,
                        () -> diagnostics.record(CheckDiagnostics.Context.WALL_HIT, fatal)));
        var death = new ThreadDeath();
        assertSame(
                death,
                assertThrows(
                        ThreadDeath.class,
                        () -> diagnostics.record(CheckDiagnostics.Context.WALL_HIT, death)));
        assertEquals(1, records.size());
    }
}
