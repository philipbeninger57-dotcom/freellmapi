package de.kyrosmp.anticheat.checks.movement;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.SetbackService;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import org.bukkit.entity.Player;

/** Test access to the package-private surroundings probe. */
public final class HoverCheckAccess {
    private HoverCheckAccess() {
    }

    public static HoverCheck create(FlagSink sink, SetbackService engine, boolean supported) {
        HoverCheck check = new HoverCheck(sink, engine, CheckConfig.defaults(), StrictConfig.defaults());
        check.probe = p -> new HoverCheck.Probe(true, supported, false, false, 80.0);
        check.floatEffect = p -> false;
        return check;
    }

    public static boolean tick(HoverCheck check, Player p, PlayerData d, long now) {
        return check.tick(p, d, now);
    }
}
