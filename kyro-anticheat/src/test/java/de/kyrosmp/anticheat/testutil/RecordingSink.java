package de.kyrosmp.anticheat.testutil;

import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** FlagSink that records every flag for assertions. */
public final class RecordingSink implements FlagSink {
    public final List<String> checks = new ArrayList<>();
    public final List<String> details = new ArrayList<>();

    @Override
    public String flag(Player player, PlayerData data, String check, String reason, String detail) {
        this.checks.add(check);
        this.details.add(detail);
        return "flag-" + this.checks.size();
    }

    @Override
    public void action(String flagId, String action) {}

    public long count(String check) {
        return this.checks.stream().filter(check::equals).count();
    }
}
