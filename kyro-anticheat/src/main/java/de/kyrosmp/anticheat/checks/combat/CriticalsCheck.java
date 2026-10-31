package de.kyrosmp.anticheat.checks.combat;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.Collisions;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerHistory;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class CriticalsCheck {
    public static final double MAX_VERTICAL_RANGE = 0.2;
    static final long WINDOW_MS = 1000L;
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    volatile Function<Player, CriticalsCheck.Surroundings> surroundings = CriticalsCheck::read;

    public CriticalsCheck(FlagSink sink, CheckConfig cfg) {
        this.sink = sink;
        this.cfg = cfg;
    }

    static CriticalsCheck.Surroundings read(Player p) {
        Location at = p.getLocation();
        World w = at.getWorld();
        BoundingBox box = Collisions.playerBox(p, at);
        return new CriticalsCheck.Surroundings(
                Collisions.isGrounded(w, box),
                Collisions.hasCeiling(w, box),
                Collisions.touchesLiquidOrClimbable(w, box));
    }

    public boolean onCritical(Player p, PlayerData d, long now) {
        CheckConfig c = this.cfg;
        if (!c.criticalsEnabled()) {
            return false;
        } else {
            CriticalsCheck.Surroundings s;
            try {
                s = this.surroundings.apply(p);
            } catch (Throwable ignored) {
                return false;
            }

            double y = p.getLocation().getY();
            double range = verticalRange(d.history.moves(), now - 1000L, y);
            if (!suspicious(s, range)) {
                d.criticalsBuffer = Math.max(0.0, d.criticalsBuffer - 0.5);
                return false;
            } else {
                d.criticalsBuffer++;
                if (d.criticalsBuffer >= c.criticalsFlagAt()) {
                    this.sink.flag(
                            p,
                            d,
                            "Criticals A",
                            "Critical hits without a real fall",
                            String.format(
                                    Locale.US,
                                    "on_ground(server)=true; vertical_range_1s=%.3f; max=%.2f;"
                                            + " buffer=%.1f",
                                    range,
                                    0.2,
                                    d.criticalsBuffer));
                    d.criticalsBuffer = 1.0;
                }

                return true;
            }
        }
    }

    public static boolean suspicious(CriticalsCheck.Surroundings s, double verticalRange) {
        return s.grounded() && !s.ceiling() && !s.liquidOrClimbable() && verticalRange < 0.2;
    }

    public static double verticalRange(
            List<PlayerHistory.MoveSample> moves, long since, double currentY) {
        double min = currentY;
        double max = currentY;

        for (PlayerHistory.MoveSample m : moves) {
            if (m.time() >= since) {
                min = Math.min(min, m.y());
                max = Math.max(max, m.y());
            }
        }

        return max - min;
    }

    public static record Surroundings(
            boolean grounded, boolean ceiling, boolean liquidOrClimbable) {}
}
