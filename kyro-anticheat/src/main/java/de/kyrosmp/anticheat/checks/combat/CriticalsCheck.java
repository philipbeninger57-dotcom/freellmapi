/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Location
 *  org.bukkit.World
 *  org.bukkit.entity.Player
 *  org.bukkit.util.BoundingBox
 */
package de.kyrosmp.anticheat.checks.combat;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.Collisions;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PlayerHistory;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

public final class CriticalsCheck {
    public static final double MAX_VERTICAL_RANGE = 0.2;
    static final long WINDOW_MS = 1000L;
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    volatile Function<Player, Surroundings> surroundings = CriticalsCheck::read;

    public CriticalsCheck(FlagSink sink, CheckConfig cfg) {
        this.sink = sink;
        this.cfg = cfg;
    }

    static Surroundings read(Player p) {
        Location at = p.getLocation();
        World w = at.getWorld();
        BoundingBox box = Collisions.playerBox(p, at);
        return new Surroundings(Collisions.isGrounded(w, box), Collisions.hasCeiling(w, box), Collisions.touchesLiquidOrClimbable(w, box));
    }

    /** @return true when this critical hit had no real fall behind it (3.7: used to strip the bonus) */
    public boolean onCritical(Player p, PlayerData d, long now) {
        Surroundings s;
        CheckConfig c = this.cfg;
        if (!c.criticalsEnabled()) {
            return false;
        }
        try {
            s = this.surroundings.apply(p);
        }
        catch (Throwable ex) {
            return false;
        }
        double y = p.getLocation().getY();
        double range = CriticalsCheck.verticalRange(d.history.moves(), now - 1000L, y);
        if (!CriticalsCheck.suspicious(s, range)) {
            d.criticalsBuffer = Math.max(0.0, d.criticalsBuffer - 0.5);
            return false;
        }
        d.criticalsBuffer += 1.0;
        if (d.criticalsBuffer >= c.criticalsFlagAt()) {
            this.sink.flag(p, d, "Criticals A", "Critical hits without a real fall", String.format(Locale.US, "on_ground(server)=true; vertical_range_1s=%.3f; max=%.2f; buffer=%.1f", range, 0.2, d.criticalsBuffer));
            d.criticalsBuffer = 1.0;
        }
        return true;
    }

    public static boolean suspicious(Surroundings s, double verticalRange) {
        return s.grounded() && !s.ceiling() && !s.liquidOrClimbable() && verticalRange < 0.2;
    }

    public static double verticalRange(List<PlayerHistory.MoveSample> moves, long since, double currentY) {
        double min = currentY;
        double max = currentY;
        for (PlayerHistory.MoveSample m : moves) {
            if (m.time() < since) continue;
            min = Math.min(min, m.y());
            max = Math.max(max, m.y());
        }
        return max - min;
    }

    public record Surroundings(boolean grounded, boolean ceiling, boolean liquidOrClimbable) {
    }
}

