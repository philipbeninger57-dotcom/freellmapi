/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.Player
 */
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

    public AutoClickerCheck(FlagSink sink, CheckConfig cfg) {
        this.sink = sink;
        this.cfg = cfg;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    private volatile StrictConfig strict = StrictConfig.defaults();
    private volatile PreventionConfig prevention = PreventionConfig.defaults();

    public AutoClickerCheck withPolicy(StrictConfig strict, PreventionConfig prevention) {
        this.strict = strict;
        this.prevention = prevention;
        return this;
    }

    /**
     * 3.7: while the arm-swing rate of the last second is above max-cps, hits do no damage.
     * Swings from item drops are excluded in {@link #onSwing}.
     */
    public boolean shouldCancelHit(PlayerData d, long now) {
        CheckConfig c = this.cfg;
        if (!c.autoClickerEnabled() || !this.strict.autoClickerCancel() || !this.prevention.actions()) {
            return false;
        }
        synchronized (d.swingTimes) {
            prune(d, now, c.autoClickerMaxCps());
            return d.swingTimes.size() > c.autoClickerMaxCps();
        }
    }

    private static void prune(PlayerData d, long now, int max) {
        while (!d.swingTimes.isEmpty() && (now - d.swingTimes.peekFirst() >= 1000L || now < d.swingTimes.peekFirst())) {
            d.swingTimes.pollFirst();
        }
        while (d.swingTimes.size() > max + 2) {
            d.swingTimes.pollFirst();
        }
    }

    public void onSwing(Player p, PlayerData d, long now) {
        CheckConfig c = this.cfg;
        if (!c.autoClickerEnabled()) {
            return;
        }
        if (d.lastDrop != 0L && now >= d.lastDrop && now - d.lastDrop < 100L) {
            // Consume each successful drop only once, and exclude at most one
            // animation per 100 ms even if a client repeatedly drops real items.
            d.lastDrop = 0L;
            if (d.lastDropSwingExemptAt == 0L || now - d.lastDropSwingExemptAt >= 100L) {
                d.lastDropSwingExemptAt = now;
                return;
            }
        }
        if (now - d.swingSecondStart >= 1000L) {
            long elapsed = now - d.swingSecondStart;
            // A window closes on the next swing, so network pauses can make it
            // longer than one second. Compare rates, not the raw window count.
            double cps = d.swingCount * 1000.0 / elapsed;
            boolean high = cps > c.autoClickerMaxCps() && elapsed < 2000L;
            int n = d.highCpsSeconds = high ? d.highCpsSeconds + 1 : 0;
            if (d.highCpsSeconds >= c.autoClickerSustainSeconds()) {
                this.sink.flag(p, d, "AutoClicker A", "Sustained impossible click rate", "cps=" + cps + "; max=" + c.autoClickerMaxCps() + "; consecutive_seconds=" + d.highCpsSeconds);
                d.highCpsSeconds = 0;
            }
            d.swingSecondStart = now;
            d.swingCount = 0;
        }
        ++d.swingCount;
        synchronized (d.swingTimes) {
            d.swingTimes.addLast(now);
            prune(d, now, c.autoClickerMaxCps());
        }
    }
}

