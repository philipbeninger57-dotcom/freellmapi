package de.kyrosmp.anticheat.checks.combat;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.entity.Player;

public final class AutoClickerCheck {
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    private volatile StrictConfig strict = StrictConfig.defaults();
    private volatile PreventionConfig prevention = PreventionConfig.defaults();

    public AutoClickerCheck(FlagSink sink, CheckConfig cfg) {
        this.sink = sink;
        this.cfg = cfg;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public AutoClickerCheck withPolicy(StrictConfig strict, PreventionConfig prevention) {
        this.strict = strict;
        this.prevention = prevention;
        return this;
    }

    public boolean shouldCancelHit(PlayerData d, long now) {
        CheckConfig c = this.cfg;
        if (c.autoClickerEnabled()
                && this.strict.autoClickerCancel()
                && this.prevention.actions()) {
            synchronized (d.swingTimes) {
                prune(d, now, c.autoClickerMaxCps());
                return d.swingTimes.size() > c.autoClickerMaxCps();
            }
        } else {
            return false;
        }
    }

    private static void prune(PlayerData d, long now, int max) {
        while (!d.swingTimes.isEmpty()
                && (now - d.swingTimes.peekFirst() >= 1000L || now < d.swingTimes.peekFirst())) {
            d.swingTimes.pollFirst();
        }

        while (d.swingTimes.size() > max + 2) {
            d.swingTimes.pollFirst();
        }
    }

    public void onSwing(Player p, PlayerData d, long now) {
        CheckConfig c = this.cfg;
        if (c.autoClickerEnabled()) {
            if (d.lastDrop != 0L && now >= d.lastDrop && now - d.lastDrop < 100L) {
                d.lastDrop = 0L;
                if (d.lastDropSwingExemptAt == 0L || now - d.lastDropSwingExemptAt >= 100L) {
                    d.lastDropSwingExemptAt = now;
                    return;
                }
            }

            if (now - d.swingSecondStart >= 1000L) {
                long elapsed = now - d.swingSecondStart;
                double cps = (double) d.swingCount * 1000.0 / (double) elapsed;
                boolean high = cps > (double) c.autoClickerMaxCps() && elapsed < 2000L;
                d.highCpsSeconds = high ? d.highCpsSeconds + 1 : 0;
                if (d.highCpsSeconds >= c.autoClickerSustainSeconds()) {
                    this.sink.flag(
                            p,
                            d,
                            "AutoClicker A",
                            "Sustained impossible click rate",
                            "cps="
                                    + cps
                                    + "; max="
                                    + c.autoClickerMaxCps()
                                    + "; consecutive_seconds="
                                    + d.highCpsSeconds);
                    d.highCpsSeconds = 0;
                }

                d.swingSecondStart = now;
                d.swingCount = 0;
            }

            d.swingCount++;
            synchronized (d.swingTimes) {
                d.swingTimes.addLast(now);
                prune(d, now, c.autoClickerMaxCps());
            }
        }
    }
}
