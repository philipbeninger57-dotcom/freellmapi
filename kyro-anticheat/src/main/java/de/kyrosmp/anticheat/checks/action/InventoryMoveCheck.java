/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.GameMode
 *  org.bukkit.entity.Player
 */
package de.kyrosmp.anticheat.checks.action;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.Locale;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

public final class InventoryMoveCheck {
    private final FlagSink sink;
    private volatile CheckConfig cfg;
    /**
     * Movement samples within this time after an inventory opened are never evidence. The
     * per-sample rule below already removes the double-click false positive, so a short grace
     * (default 25 ms, i.e. samples from the opening tick itself, configurable) keeps fast inventory
     * cheats such as AutoArmor detectable.
     */
    private volatile long openGraceMs = StrictConfig.DEFAULT_INVENTORY_OPEN_GRACE_MS;

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

    /** Container opens are observable; the client-only player inventory is first seen on click. */
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

    /** @return true when this click happened while clearly moving (3.7: used to cancel the click) */
    public boolean onClick(Player p, PlayerData d, long now) {
        boolean moving;
        CheckConfig c = this.cfg;
        if (!c.inventoryMoveEnabled()) {
            return false;
        }
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.isInsideVehicle() || p.isGliding() || p.isFlying() || (c.bedrockExempt() && d.bedrock == Boolean.TRUE) || now - d.lastSlipperySurface < 1500L || now < d.velocityGraceUntil || now - d.lastLiquid < 600L || now - d.lastTeleport < 1000L || now - d.lastDamage < 1000L) {
            return false;
        }
        if (!d.inventorySessionActive || now < d.inventoryOpenedAt) {
            this.onOpen(d, now);
            return false;
        }
        // A move recorded before the screen opened cannot prove movement with the screen open.
        // Allow released keys and residual movement to settle before collecting evidence.
        long grace = this.openGraceMs;
        // Strictly after the opening: a move sampled at the same instant can predate the screen.
        if (now - d.inventoryOpenedAt < grace || d.lastMove - d.inventoryOpenedAt < Math.max(1L, grace)) {
            return false;
        }
        moving = now >= d.lastMove && now - d.lastMove <= 120L && d.lastGround
                && d.lastMoveHorizontal > c.inventoryMoveMinSpeed()
                && !InventoryMoveCheck.decaying(d.lastMoveHorizontal, d.prevMoveHorizontal);
        if (moving) {
            // Several clicks against one old movement sample are only one piece of evidence.
            boolean newEvidence = d.lastMove > d.inventoryEvidenceMoveAt;
            if (newEvidence) {
                d.inventoryMoveBuffer += 1.0;
                d.inventoryEvidenceMoveAt = d.lastMove;
            }
            boolean cancel = d.inventoryMoveBuffer >= 2.0;
            if (newEvidence && d.inventoryMoveBuffer >= c.inventoryMoveFlagAt()) {
                this.sink.flag(p, d, "InventoryMove A", "Moving on the ground while an inventory is open", String.format(Locale.US, "horizontal=%.3f; min=%.3f; ms_since_move=%d; buffer=%.1f", d.lastMoveHorizontal, c.inventoryMoveMinSpeed(), now - d.lastMove, d.inventoryMoveBuffer));
                // Keep established prevention active for further clicks on this same sample.
                d.inventoryMoveBuffer = Math.min(d.inventoryMoveBuffer, 2.0);
            }
            return cancel;
        } else {
            d.inventoryMoveBuffer = Math.max(0.0, d.inventoryMoveBuffer - 0.5);
        }
        return false;
    }
}

