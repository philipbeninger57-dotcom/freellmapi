package de.kyrosmp.anticheat.checks.action;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class InventoryMoveCheck {
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    private volatile long openGraceMs = 25L;

    public InventoryMoveCheck(FlagSink sink, CheckConfig cfg) {
        this.sink = sink;
        this.cfg = cfg;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public InventoryMoveCheck withStrict(StrictConfig strict) {
        this.openGraceMs = Math.max(0L, strict.inventoryOpenGraceMs());
        return this;
    }

    public long openGraceMs() {
        return this.openGraceMs;
    }

    static boolean decaying(double last, double previous) {
        return previous > 0.0 && last <= previous * 0.75;
    }

    public void onOpen(PlayerData d, long now) {
        d.inventorySessionActive = true;
        d.inventoryOpenedAt = now;
        d.inventoryEvidenceMoveAt = 0L;
        d.inventoryMoveBuffer = 0.0;
    }

    public void onClose(PlayerData d) {
        d.inventorySessionActive = false;
        d.inventoryOpenedAt = 0L;
        d.inventoryEvidenceMoveAt = 0L;
        d.inventoryMoveBuffer = 0.0;
    }

    public boolean onClick(Player p, PlayerData d, long now) {
        CheckConfig c = this.cfg;
        if (!c.inventoryMoveEnabled()) {
            return false;
        } else {
            GameMode gm = p.getGameMode();
            if (gm == GameMode.CREATIVE
                    || gm == GameMode.SPECTATOR
                    || p.isInsideVehicle()
                    || p.isGliding()
                    || p.isFlying()
                    || c.bedrockExempt() && d.bedrock == Boolean.TRUE
                    || now - d.lastSlipperySurface < 1500L
                    || now < d.velocityGraceUntil
                    || now - d.lastLiquid < 600L
                    || now - d.lastTeleport < 1000L
                    || now - d.lastDamage < 1000L) {
                return false;
            } else if (d.inventorySessionActive && now >= d.inventoryOpenedAt) {
                long grace = this.openGraceMs;
                if (now - d.inventoryOpenedAt >= grace
                        && d.lastMove - d.inventoryOpenedAt >= Math.max(1L, grace)) {
                    boolean moving =
                            now >= d.lastMove
                                    && now - d.lastMove <= 120L
                                    && d.lastGround
                                    && d.lastMoveHorizontal > c.inventoryMoveMinSpeed()
                                    && !decaying(d.lastMoveHorizontal, d.prevMoveHorizontal);
                    if (moving) {
                        boolean newEvidence = d.lastMove > d.inventoryEvidenceMoveAt;
                        if (newEvidence) {
                            d.inventoryMoveBuffer++;
                            d.inventoryEvidenceMoveAt = d.lastMove;
                        }

                        boolean cancel = d.inventoryMoveBuffer >= 2.0;
                        if (newEvidence && d.inventoryMoveBuffer >= c.inventoryMoveFlagAt()) {
                            this.sink.flag(
                                    p,
                                    d,
                                    "InventoryMove A",
                                    "Moving on the ground while an inventory is open",
                                    String.format(
                                            Locale.US,
                                            "horizontal=%.3f; min=%.3f; ms_since_move=%d;"
                                                    + " buffer=%.1f",
                                            d.lastMoveHorizontal,
                                            c.inventoryMoveMinSpeed(),
                                            now - d.lastMove,
                                            d.inventoryMoveBuffer));
                            d.inventoryMoveBuffer = Math.min(d.inventoryMoveBuffer, 2.0);
                        }

                        return cancel;
                    } else {
                        d.inventoryMoveBuffer = Math.max(0.0, d.inventoryMoveBuffer - 0.5);
                        return false;
                    }
                } else {
                    return false;
                }
            } else {
                this.onOpen(d, now);
                return false;
            }
        }
    }
}
