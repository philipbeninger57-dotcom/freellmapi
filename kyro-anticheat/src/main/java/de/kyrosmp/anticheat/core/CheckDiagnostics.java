package de.kyrosmp.anticheat.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CheckDiagnostics {
    private static final long INTERVAL_MS = 60000L;
    private static final CheckDiagnostics DEFAULT =
            new CheckDiagnostics(
                    Logger.getLogger("KYRO-AntiCheat"), () -> System.nanoTime() / 1000000L);
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<CheckDiagnostics.Context, CheckDiagnostics.Window> windows =
            new EnumMap<>(CheckDiagnostics.Context.class);

    CheckDiagnostics(Logger logger, LongSupplier clock) {
        this.logger = Objects.requireNonNull(logger);
        this.clock = Objects.requireNonNull(clock);
    }

    public static void report(CheckDiagnostics.Context context, Throwable failure) {
        DEFAULT.record(context, failure);
    }

    void record(CheckDiagnostics.Context context, Throwable failure) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(failure);
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        } else if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        } else {
            long suppressed;
            synchronized (this.windows) {
                long now = this.clock.getAsLong();
                CheckDiagnostics.Window window =
                        this.windows.computeIfAbsent(
                                context, ignored -> new CheckDiagnostics.Window());
                if (window.logged && now >= window.lastLogAt && now - window.lastLogAt < 60000L) {
                    if (window.suppressed < Long.MAX_VALUE) {
                        window.suppressed++;
                    }

                    return;
                }

                suppressed = window.suppressed;
                window.suppressed = 0L;
                window.lastLogAt = now;
                window.logged = true;
            }

            this.logger.log(
                    Level.WARNING,
                    "[KYRO-AntiCheat] "
                            + context.description
                            + " failed; check processing for this sample was incomplete."
                            + " suppressed_since_last_log="
                            + suppressed
                            + ". Check server/API compatibility and the stack trace below.",
                    failure);
        }
    }

    public static enum Context {
        COMBAT_ATTACK("Reach/KillAura attack processing"),
        WALL_HIT("KillAura wall visibility"),
        TARGET_SWITCH("KillAura target direction"),
        GHOST_HAND("GhostHand block visibility");

        private final String description;

        private Context(String description) {
            this.description = description;
        }
    }

    private static final class Window {
        private boolean logged;
        private long lastLogAt;
        private long suppressed;
    }
}
