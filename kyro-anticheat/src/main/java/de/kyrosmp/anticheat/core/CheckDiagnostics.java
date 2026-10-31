package de.kyrosmp.anticheat.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reports failed check samples without letting repeated API errors flood the server log. */
public final class CheckDiagnostics {
    private static final long INTERVAL_MS = 60_000L;
    private static final CheckDiagnostics DEFAULT = new CheckDiagnostics(
            Logger.getLogger("KYRO-AntiCheat"), () -> System.nanoTime() / 1_000_000L);

    /** Fixed contexts keep state bounded; never use a player name, UUID, or exception message as a key. */
    public enum Context {
        COMBAT_ATTACK("Reach/KillAura attack processing"),
        WALL_HIT("KillAura wall visibility"),
        TARGET_SWITCH("KillAura target direction"),
        GHOST_HAND("GhostHand block visibility");

        private final String description;

        Context(String description) {
            this.description = description;
        }
    }

    private final Logger logger;
    private final LongSupplier clock;
    private final Map<Context, Window> windows = new EnumMap<>(Context.class);

    /** The clock supplies monotonic milliseconds; injectable for deterministic tests. */
    CheckDiagnostics(Logger logger, LongSupplier clock) {
        this.logger = Objects.requireNonNull(logger);
        this.clock = Objects.requireNonNull(clock);
    }

    public static void report(Context context, Throwable failure) {
        DEFAULT.record(context, failure);
    }

    void record(Context context, Throwable failure) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(failure);
        // Resource exhaustion and VM shutdown cannot be made safe by skipping a check.
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;

        long suppressed;
        synchronized (windows) {
            long now = clock.getAsLong();
            Window window = windows.computeIfAbsent(context, ignored -> new Window());
            if (window.logged && now >= window.lastLogAt && now - window.lastLogAt < INTERVAL_MS) {
                if (window.suppressed < Long.MAX_VALUE) window.suppressed++;
                return;
            }
            suppressed = window.suppressed;
            window.suppressed = 0L;
            window.lastLogAt = now;
            window.logged = true;
        }
        logger.log(Level.WARNING, "[KYRO-AntiCheat] " + context.description
                + " failed; check processing for this sample was incomplete."
                + " suppressed_since_last_log=" + suppressed
                + ". Check server/API compatibility and the stack trace below.", failure);
    }

    private static final class Window {
        private boolean logged;
        private long lastLogAt;
        private long suppressed;
    }
}
