package de.kyrosmp.anticheat.checks.movement;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.Collisions;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.SetbackService;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.function.Function;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

/**
 * Corrects an unsupported position after prolonged position silence. This heartbeat runs
 * on the player's own scheduler and can return the player to validated ground.
 * Position silence can also be caused by a stalled client or connection: without packet
 * acknowledgements it is not evidence of cheating, so this correction never creates a flag
 * or increases suspicion. Move-based checks evaluate actual movement separately.
 */
public final class HoverCheck {
    /** Consecutive heartbeat confirmations required after the still period. */
    static final int CONFIRMATIONS = 2;

    private final SetbackService setback;
    private volatile CheckConfig cfg;
    private volatile StrictConfig strict;
    private volatile PreventionConfig prevention = PreventionConfig.defaults();
    volatile Function<Player, Probe> probe = HoverCheck::probe;
    /** Levitation / slow falling; replaceable in tests (potion registries need a server). */
    volatile java.util.function.Predicate<Player> floatEffect = HoverCheck::floatEffect;

    public HoverCheck(FlagSink sink, SetbackService setback, CheckConfig cfg, StrictConfig strict) {
        this.setback = setback;
        this.cfg = cfg;
        this.strict = strict;
    }

    public HoverCheck withPrevention(PreventionConfig policy) {
        this.prevention = policy;
        return this;
    }

    public void configure(CheckConfig cfg, StrictConfig strict) {
        this.cfg = cfg;
        this.strict = strict;
    }

    /** What surrounds the player right now (server view). */
    public record Probe(boolean readable, boolean supported, boolean special, boolean platform, double y) {
    }

    /** @return true when a setback was requested */
    public boolean tick(Player p, PlayerData d, long now) {
        StrictConfig s = this.strict;
        CheckConfig c = this.cfg;
        if (!s.hoverEnabled() || !c.flyEnabled() || this.exemptState(p)) {
            d.hoverStillChecks = 0;
            return false;
        }
        if (now - d.lastPositionChangeAt < s.hoverStillMs()
                || now - d.lastTeleport < c.graceTeleportMs() + 1000L
                || d.hasFreshPendingSetback(now)
                || now - d.lastGhostBlock < 3000L
                || now - d.lastVehicle < 2000L
                || now - d.lastGlide < c.graceElytraMs()
                || now - d.lastRiptide < c.graceRiptideMs()
                || now - d.lastLiquid < 1500L
                || now - d.lastBounceSurface < 2000L) {
            d.hoverStillChecks = 0;
            return false;
        }
        Probe pr;
        try {
            pr = this.probe.apply(p);
        }
        catch (Throwable ex) {
            pr = null;
        }
        if (pr == null || !pr.readable() || pr.supported() || pr.special() || pr.platform()) {
            d.hoverStillChecks = 0;
            return false;
        }
        if (++d.hoverStillChecks < CONFIRMATIONS) {
            return false;
        }
        d.hoverStillChecks = 0;
        boolean prevent = this.prevention.movement() && c.flySetback();
        boolean done = prevent && this.setback.setback(p, d, null);
        // Position silence alone cannot distinguish a frozen client from a cheat.
        // Correct the position without adding evidence to flag history or confidence.
        // Restart the timer so failed/unavailable corrections are not retried every heartbeat.
        d.lastPositionChangeAt = now;
        return done;
    }

    private boolean exemptState(Player p) {
        try {
            if (!p.isOnline() || p.isDead() || p.isSleeping()) return true;
            GameMode gm = p.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
            if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isInsideVehicle() || p.isRiptiding()
                    || p.isSwimming() || !p.hasGravity()) return true;
            return this.floatEffect.test(p);
        }
        catch (Throwable ex) {
            return true;
        }
    }

    static boolean floatEffect(Player p) {
        return p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    static Probe probe(Player p) {
        Location at = p.getLocation();
        World w = at.getWorld();
        BoundingBox box = Collisions.playerBox(p, at);
        if (w == null || !Collisions.queryable(w, box.clone().expand(1.6, 1.6, 1.6))) {
            return new Probe(false, true, true, true, at.getY());
        }
        boolean supported = Collisions.nearGround(w, box);
        boolean special = !supported && (Collisions.classify(w, box.clone().expand(0.3, 0.3, 0.3)) != 0
                || Collisions.movingBlockNearby(w, box));
        boolean platform = !supported && !special && Collisions.platformEntityNearby(p);
        return new Probe(true, supported, special, platform, at.getY());
    }
}
