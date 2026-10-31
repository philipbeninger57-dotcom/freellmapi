package de.kyrosmp.anticheat.checks.movement;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.Collisions;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.SetbackService;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.function.Function;
import java.util.function.Predicate;

public final class HoverCheck {
    static final int CONFIRMATIONS = 2;
    private final SetbackService setback;
    private volatile CheckConfig cfg;
    private volatile StrictConfig strict;
    private volatile PreventionConfig prevention = PreventionConfig.defaults();
    volatile Function<Player, HoverCheck.Probe> probe = HoverCheck::probe;
    volatile Predicate<Player> floatEffect = HoverCheck::floatEffect;

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

    public boolean tick(Player p, PlayerData d, long now) {
        StrictConfig s = this.strict;
        CheckConfig c = this.cfg;
        if (s.hoverEnabled() && c.flyEnabled() && !this.exemptState(p)) {
            if (now - d.lastPositionChangeAt >= s.hoverStillMs()
                    && now - d.lastTeleport >= c.graceTeleportMs() + 1000L
                    && !d.hasFreshPendingSetback(now)
                    && now - d.lastGhostBlock >= 3000L
                    && now - d.lastVehicle >= 2000L
                    && now - d.lastGlide >= c.graceElytraMs()
                    && now - d.lastRiptide >= c.graceRiptideMs()
                    && now - d.lastLiquid >= 1500L
                    && now - d.lastBounceSurface >= 2000L) {
                HoverCheck.Probe pr;
                try {
                    pr = this.probe.apply(p);
                } catch (Throwable ignored) {
                    pr = null;
                }

                if (pr == null
                        || !pr.readable()
                        || pr.supported()
                        || pr.special()
                        || pr.platform()) {
                    d.hoverStillChecks = 0;
                    return false;
                } else if (++d.hoverStillChecks < 2) {
                    return false;
                } else {
                    d.hoverStillChecks = 0;
                    boolean prevent = this.prevention.movement() && c.flySetback();
                    boolean done = prevent && this.setback.setback(p, d, null);
                    d.lastPositionChangeAt = now;
                    return done;
                }
            } else {
                d.hoverStillChecks = 0;
                return false;
            }
        } else {
            d.hoverStillChecks = 0;
            return false;
        }
    }

    private boolean exemptState(Player p) {
        try {
            if (p.isOnline() && !p.isDead() && !p.isSleeping()) {
                GameMode gm = p.getGameMode();
                if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
                    return true;
                } else {
                    return !p.getAllowFlight()
                                    && !p.isFlying()
                                    && !p.isGliding()
                                    && !p.isInsideVehicle()
                                    && !p.isRiptiding()
                                    && !p.isSwimming()
                                    && p.hasGravity()
                            ? this.floatEffect.test(p)
                            : true;
                }
            } else {
                return true;
            }
        } catch (Throwable ignored) {
            return true;
        }
    }

    static boolean floatEffect(Player p) {
        return p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    static HoverCheck.Probe probe(Player p) {
        Location at = p.getLocation();
        World w = at.getWorld();
        BoundingBox box = Collisions.playerBox(p, at);
        if (w != null && Collisions.queryable(w, box.clone().expand(1.6, 1.6, 1.6))) {
            boolean supported = Collisions.nearGround(w, box);
            boolean special =
                    !supported
                            && (Collisions.classify(w, box.clone().expand(0.3, 0.3, 0.3)) != 0
                                    || Collisions.movingBlockNearby(w, box));
            boolean platform = !supported && !special && Collisions.platformEntityNearby(p);
            return new HoverCheck.Probe(true, supported, special, platform, at.getY());
        } else {
            return new HoverCheck.Probe(false, true, true, true, at.getY());
        }
    }

    public static record Probe(
            boolean readable, boolean supported, boolean special, boolean platform, double y) {}
}
