package de.kyrosmp.anticheat.checks.vehicle;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.Collisions;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.flag.FlagSink;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.Locale;
import java.util.UUID;
import java.util.function.BiPredicate;

public final class BoatFlyCheck {
    public static final double BOAT_GRAVITY = 0.04;
    private final FlagSink sink;
    private final Punisher punisher;
    private volatile CheckConfig cfg;
    private PreventionConfig prevention = PreventionConfig.defaults();
    BiPredicate<Boat, Location> applySetback;

    public BoatFlyCheck withPrevention(PreventionConfig policy) {
        this.prevention = policy;
        return this;
    }

    public BoatFlyCheck(FlagSink sink, Punisher punisher, CheckConfig cfg) {
        this.sink = sink;
        this.punisher = punisher;
        this.cfg = cfg;
        this.applySetback = punisher == null ? (b, t) -> false : punisher::setbackBoat;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public void check(Player p, PlayerData d, Boat boat, Location from, Location to, long now) {
        CheckConfig c = this.cfg;
        d.lastVehicle = now;
        UUID boatId = boat.getUniqueId();
        if (!boatId.equals(d.boatId)) {
            d.resetBoatState(boatId);
        }

        if (c.boatEnabled() && boat.hasGravity()) {
            World w = to.getWorld();
            BoundingBox box;
            boolean supported =
                    Collisions.isGrounded(w, box = boat.getBoundingBox())
                            || Collisions.touchesLiquidOrClimbable(
                                    w, box.clone().expand(0.0, 0.3, 0.0));
            this.evaluate(p, d, boat, from, to, supported, now);
        } else {
            d.boatHoverSince = 0L;
            d.boatHoverLastAt = 0L;
            d.boatFlyBuffer = Math.max(0.0, d.boatFlyBuffer - 0.65);
        }
    }

    void evaluate(
            Player p,
            PlayerData d,
            Boat boat,
            Location from,
            Location to,
            boolean supported,
            long now) {
        CheckConfig c = this.cfg;
        double dy = to.getY() - from.getY();
        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (supported) {
            d.boatAirSince = 0L;
            d.lastBoatSupported = now;
            d.lastBoatSafe = to.clone();
            d.lastBoatSafeAt = now;
        }

        double previousDy = d.lastBoatDy;
        d.lastBoatDy = supported ? Double.NaN : dy;
        boolean airborne = !supported && now - d.lastBoatSupported > 250L;
        if (airborne && d.boatAirSince == 0L) {
            d.boatAirSince = now;
        }

        boolean ballistic = isBallistic(previousDy, dy);
        boolean rising = dy > 0.12 && !ballistic;
        boolean sustainedHover =
                this.sustainedSlowHover(
                        d, Math.abs(dy) < 0.012 && airborne && now >= d.velocityGraceUntil, now);
        boolean hovering = Math.abs(dy) < 0.012 && (horizontal > 0.16 || sustainedHover);
        boolean proven =
                airborne
                        && now - d.boatAirSince >= 150L
                        && now >= d.velocityGraceUntil
                        && (rising && Double.isFinite(previousDy) || sustainedHover);
        if (this.prevention.instant() && c.boatSetback() && proven) {
            d.preventMove = true;
            this.preventBoat(d, boat, to, now);
        }

        d.boatFlyBuffer =
                airborne && now >= d.velocityGraceUntil && (rising || hovering)
                        ? d.boatFlyBuffer + (rising ? 1.0 : 0.58)
                        : Math.max(0.0, d.boatFlyBuffer - 0.65);
        if (d.boatFlyBuffer >= c.boatFlagAt()) {
            String id =
                    this.sink.flag(
                            p,
                            d,
                            "BoatFly A",
                            rising
                                    ? "Boat rising without support"
                                    : "Boat hovering without support",
                            String.format(
                                    Locale.US,
                                    "dy=%.3f; previous_dy=%s; horizontal=%.3f; buffer=%.2f",
                                    dy,
                                    Double.isNaN(previousDy)
                                            ? "-"
                                            : String.format(Locale.US, "%.3f", previousDy),
                                    horizontal,
                                    d.boatFlyBuffer));
            if (this.prevention.movement() && c.boatSetback() && boat != null && proven) {
                this.preventBoat(d, boat, to, now);
                if (id != null) {
                    this.sink.action(id, "Boat set back");
                }
            }

            d.boatFlyBuffer = 0.5;
            if (id != null) {
                this.punisher.registerBoatFlyPunishment(p, d, now, id);
            }
        }
    }

    private void preventBoat(PlayerData d, Boat boat, Location to, long now) {
        if (boat != null) {
            if (d.boatSetbackAt == 0L || now < d.boatSetbackAt || now - d.boatSetbackAt >= 500L) {
                Location target = d.lastBoatSafe;
                if (target != null
                        && target.getWorld() == to.getWorld()
                        && this.applySetback.test(boat, target)) {
                    d.boatSetbackAt = now;
                }
            }
        }
    }

    private boolean sustainedSlowHover(PlayerData d, boolean qualifying, long now) {
        if (!qualifying) {
            d.boatHoverSince = 0L;
            d.boatHoverLastAt = 0L;
            return false;
        } else {
            if (d.boatHoverSince == 0L
                    || now < d.boatHoverLastAt
                    || now - d.boatHoverLastAt > 250L) {
                d.boatHoverSince = now;
            }

            d.boatHoverLastAt = now;
            return now - d.boatHoverSince >= 1000L;
        }
    }

    public static boolean isBallistic(double previousDy, double dy) {
        return Double.isNaN(previousDy) ? false : dy <= previousDy - 0.024;
    }
}
