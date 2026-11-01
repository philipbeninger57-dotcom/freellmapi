package de.kyrosmp.anticheat.checks.combat;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.CheckDiagnostics;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * KillAura D: melee attacks without an arm swing.
 *
 * <p>A vanilla client always sends the swing right after the attack packet, in the same tick and on
 * the same ordered connection. Many kill auras and "no swing" modules skip it. Each attack is
 * re-checked {@value #DELAY_TICKS} ticks later on the player's own scheduler (Folia-safe): if no
 * swing arrived in between, the buffer grows. While the buffer is high, further hits are cancelled.
 *
 * <p>Spear attacks (1.21.11 lunge/jab) are skipped because their swing behaviour differs.
 */
public final class NoSwingCheck {
    static final long DELAY_TICKS = 5L;

    private final Plugin plugin;
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    private volatile StrictConfig strict;
    private volatile PreventionConfig prevention;

    public NoSwingCheck(
            Plugin plugin,
            FlagSink sink,
            CheckConfig cfg,
            StrictConfig strict,
            PreventionConfig prevention) {
        this.plugin = plugin;
        this.sink = sink;
        this.cfg = cfg;
        this.strict = strict;
        this.prevention = prevention;
    }

    /** Every arm swing packet, cancelled or not. */
    public static void onSwing(PlayerData d) {
        d.swingSeq++;
    }

    /**
     * Schedules the swing verification for this attack.
     *
     * @return {@code true} if the hit should be cancelled because earlier attacks had no swing
     */
    public boolean onAttack(Player p, PlayerData d) {
        if (!this.active() || holdingSpear(p)) {
            return false;
        }

        long seq = d.swingSeq;
        try {
            p.getScheduler()
                    .runDelayed(this.plugin, task -> this.evaluate(p, d, seq), null, DELAY_TICKS);
        } catch (Throwable ex) {
            CheckDiagnostics.report(CheckDiagnostics.Context.COMBAT_ATTACK, ex);
            return false;
        }

        double cancelAt = Math.min(2.0, this.strict.noSwingFlagAt());
        return d.noSwingBuffer >= cancelAt
                && this.cfg.killAuraCancel()
                && this.prevention.actions();
    }

    void evaluate(Player p, PlayerData d, long seqAtAttack) {
        if (!p.isOnline() || !this.active()) {
            return;
        }
        if (d.swingSeq != seqAtAttack) {
            d.noSwingBuffer = Math.max(0.0, d.noSwingBuffer - 0.25);
            return;
        }

        d.noSwingBuffer++;
        if (d.noSwingBuffer >= this.strict.noSwingFlagAt()) {
            this.sink.flag(
                    p,
                    d,
                    "KillAura D",
                    "Attack without arm swing",
                    String.format(
                            Locale.US,
                            "no swing within %d ticks after the attack; buffer=%.1f; ping=%d",
                            DELAY_TICKS,
                            d.noSwingBuffer,
                            Punisher.safePing(p)));
            d.noSwingBuffer = 1.0;
        }
    }

    private boolean active() {
        return this.strict.noSwingEnabled() && this.cfg.killAuraEnabled();
    }

    private static boolean holdingSpear(Player p) {
        try {
            ItemStack hand = p.getInventory().getItemInMainHand();
            return hand != null && hand.getType().name().endsWith("_SPEAR");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
