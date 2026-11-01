package de.kyrosmp.anticheat.checks.combat;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.Collisions;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Locale;

public final class VelocityCheck {
    private final Plugin plugin;
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    static final long VELOCITY_EVENT_MATCH_MS = 250L;

    public VelocityCheck(Plugin plugin, FlagSink sink, CheckConfig cfg) {
        this.plugin = plugin;
        this.sink = sink;
        this.cfg = cfg;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public void onKnockback(Player p, PlayerData d, Vector knockback, long now) {
        CheckConfig c = this.cfg;
        if (c.velocityEnabled() && knockback != null) {
            double h = Math.hypot(knockback.getX(), knockback.getZ());
            if (!(h < c.velocityMinKnockback()) && !this.exempt(p, d, now)) {
                long kbTime = now;
                d.kbTime = now;
                d.kbDirX = knockback.getX() / h;
                d.kbDirZ = knockback.getZ() / h;
                d.kbStrength = h;
                d.kbMaxProjection = 0.0;
                d.kbMoves = 0;
                long delayTicks =
                        (long) Math.max(8, Math.min(30, (Punisher.safePing(p) + 350) / 50));

                try {
                    p.getScheduler()
                            .runDelayed(
                                    this.plugin,
                                    task -> this.evaluate(p, d, kbTime),
                                    null,
                                    delayTicks);
                } catch (Throwable ignored) {
                    d.kbTime = 0L;
                }
            } else {
                d.kbTime = 0L;
            }
        }
    }

    public void onMove(PlayerData d, double dx, double dz, long now) {
        if (d.kbTime != 0L && now - d.kbTime <= 1500L) {
            double projection = dx * d.kbDirX + dz * d.kbDirZ;
            if (projection > d.kbMaxProjection) {
                d.kbMaxProjection = projection;
            }

            d.kbMoves++;
        }
    }

    public void onVelocityEvent(
            PlayerData d, Vector sent, boolean cancelled, boolean modified, long now) {
        if (d.kbTime != 0L && now - d.kbTime <= 250L) {
            if (cancelled) {
                d.kbTime = 0L;
            } else if (modified && sent != null) {
                double h = Math.hypot(sent.getX(), sent.getZ());
                if (h < this.cfg.velocityMinKnockback()) {
                    d.kbTime = 0L;
                } else {
                    d.kbDirX = sent.getX() / h;
                    d.kbDirZ = sent.getZ() / h;
                    d.kbStrength = h;
                }
            }
        }
    }

    public void cancelPending(PlayerData d) {
        d.kbTime = 0L;
    }

    private void evaluate(Player p, PlayerData d, long kbTime) {
        if (d.kbTime == kbTime) {
            d.kbTime = 0L;
            CheckConfig c = this.cfg;
            long now = System.currentTimeMillis();
            if (c.velocityEnabled() && !this.exempt(p, d, now)) {
                if (!blocked(p, d)) {
                    double expectedMin = d.kbStrength * 0.1;
                    if (d.kbMaxProjection < expectedMin) {
                        d.velocityBuffer++;
                        if (d.velocityBuffer >= c.velocityFlagAt()) {
                            this.sink.flag(
                                    p,
                                    d,
                                    "Velocity A",
                                    "Knockback almost entirely ignored",
                                    String.format(
                                            Locale.US,
                                            "knockback=%.3f; max_movement_along_kb=%.3f;"
                                                    + " expected>=%.3f; moves=%d; ping=%d",
                                            d.kbStrength,
                                            d.kbMaxProjection,
                                            expectedMin,
                                            d.kbMoves,
                                            Punisher.safePing(p)));
                            d.velocityBuffer = 1.0;
                        }
                    } else {
                        d.velocityBuffer = Math.max(0.0, d.velocityBuffer - 0.5);
                    }
                }
            }
        }
    }

    private boolean exempt(Player p, PlayerData d, long now) {
        GameMode gm = p.getGameMode();
        return p.isDead()
                || gm == GameMode.CREATIVE
                || gm == GameMode.SPECTATOR
                || p.getAllowFlight()
                || p.isFlying()
                || p.isGliding()
                || p.isInsideVehicle()
                || p.isSwimming()
                || p.isRiptiding()
                || now - d.lastTeleport < 1000L
                || now - d.lastLiquid < 600L
                || this.cfg.bedrockExempt() && d.bedrock == Boolean.TRUE;
    }

    private static boolean blocked(Player p, PlayerData d) {
        try {
            World w = p.getWorld();
            BoundingBox box = Collisions.playerBox(p, p.getLocation());
            if (Collisions.touchesLiquidOrClimbable(w, box)) {
                return true;
            } else {
                BoundingBox shifted = box.clone().shift(d.kbDirX * 0.35, 0.05, d.kbDirZ * 0.35);
                return w.hasCollisionsIn(shifted);
            }
        } catch (Throwable ignored) {
            return true;
        }
    }
}
