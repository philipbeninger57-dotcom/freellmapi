/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Location
 *  org.bukkit.World
 *  org.bukkit.entity.Boat
 *  org.bukkit.entity.Player
 *  org.bukkit.util.BoundingBox
 */
package de.kyrosmp.anticheat.checks.vehicle;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.Collisions;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.Punisher;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

public final class BoatFlyCheck {
    public static final double BOAT_GRAVITY = 0.04;
    private final FlagSink sink;
    private final Punisher punisher;
    private volatile CheckConfig cfg;
    private PreventionConfig prevention = PreventionConfig.defaults();
    java.util.function.BiPredicate<Boat, Location> applySetback;
    public BoatFlyCheck withPrevention(PreventionConfig policy) { prevention = policy; return this; }

    public BoatFlyCheck(FlagSink sink, Punisher punisher, CheckConfig cfg) {
        this.sink = sink;
        this.punisher = punisher;
        this.cfg = cfg;
        this.applySetback = punisher == null ? (b,t) -> false : punisher::setbackBoat;
    }

    public void configure(CheckConfig cfg) {
        this.cfg = cfg;
    }

    public void check(Player p, PlayerData d, Boat boat, Location from, Location to, long now) {
        BoundingBox box;
        CheckConfig c = this.cfg;
        d.lastVehicle = now;
        UUID boatId = boat.getUniqueId();
        if (!boatId.equals(d.boatId)) {
            d.resetBoatState(boatId);
        }
        if (!c.boatEnabled() || !boat.hasGravity()) {
            // Gravity can be disabled by the server or another plugin.
            d.boatHoverSince = 0L;
            d.boatHoverLastAt = 0L;
            d.boatFlyBuffer = Math.max(0.0, d.boatFlyBuffer - 0.65);
            return;
        }
        World w = to.getWorld();
        boolean supported = Collisions.isGrounded(w, box = boat.getBoundingBox()) || Collisions.touchesLiquidOrClimbable(w, box.clone().expand(0.0, 0.3, 0.0));
        this.evaluate(p, d, boat, from, to, supported, now);
    }

    void evaluate(Player p, PlayerData d, Boat boat, Location from, Location to, boolean supported, long now) {
        boolean hovering;
        CheckConfig c = this.cfg;
        double dy = to.getY() - from.getY();
        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (supported) {
            d.boatAirSince=0;
            d.lastBoatSupported = now;
            d.lastBoatSafe = to.clone();
            d.lastBoatSafeAt = now;
        }
        double previousDy = d.lastBoatDy;
        d.lastBoatDy = supported ? Double.NaN : dy;
        boolean airborne = !supported && now - d.lastBoatSupported > 250L;
        if (airborne && d.boatAirSince==0) d.boatAirSince=now;
        boolean ballistic = BoatFlyCheck.isBallistic(previousDy, dy);
        boolean rising = dy > 0.12 && !ballistic;
        // Near-zero vertical motion also matters when the boat barely moves forward.
        // Newly covered slow/stationary hover needs sustained wall-clock evidence:
        // bursts at a legitimate apex must not fill an incident in one server tick.
        boolean sustainedHover = this.sustainedSlowHover(d, Math.abs(dy) < 0.012
                && airborne && now >= d.velocityGraceUntil, now);
        hovering = Math.abs(dy) < 0.012 && (horizontal > 0.16 || sustainedHover);
        boolean proven = airborne && now-d.boatAirSince>=150 && now >= d.velocityGraceUntil &&
                ((rising && Double.isFinite(previousDy)) || sustainedHover);
        if (prevention.instant() && c.boatSetback() && proven) {
            d.preventMove = true;
            preventBoat(d, boat, to, now);
        }
        d.boatFlyBuffer = airborne && now >= d.velocityGraceUntil && (rising || hovering) ? d.boatFlyBuffer + (rising ? 1.0 : 0.58) : Math.max(0.0, d.boatFlyBuffer - 0.65);
        if (d.boatFlyBuffer >= c.boatFlagAt()) {
            String id = this.sink.flag(p, d, "BoatFly A", rising ? "Boat rising without support" : "Boat hovering without support", String.format(Locale.US, "dy=%.3f; previous_dy=%s; horizontal=%.3f; buffer=%.2f", dy, Double.isNaN(previousDy) ? "-" : String.format(Locale.US, "%.3f", previousDy), horizontal, d.boatFlyBuffer));
            if (prevention.movement() && c.boatSetback() && boat != null && proven) {
                preventBoat(d, boat, to, now);
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
        if (boat == null) return;
        // VehicleMoveEvent cannot be cancelled. Retain one validated anchor;
        // never use the latest cheating 'from' position as a fallback.
        if (d.boatSetbackAt != 0 && now >= d.boatSetbackAt && now - d.boatSetbackAt < 500) return;
        Location target = d.lastBoatSafe;
        if (target != null && target.getWorld() == to.getWorld() && applySetback.test(boat, target)) {
            d.boatSetbackAt = now;
        }
    }

    private boolean sustainedSlowHover(PlayerData d, boolean qualifying, long now) {
        if (!qualifying) {
            d.boatHoverSince = 0L;
            d.boatHoverLastAt = 0L;
            return false;
        }
        if (d.boatHoverSince == 0L || now < d.boatHoverLastAt || now - d.boatHoverLastAt > 250L) {
            d.boatHoverSince = now;
        }
        d.boatHoverLastAt = now;
        return now - d.boatHoverSince >= 1000L;
    }

    public static boolean isBallistic(double previousDy, double dy) {
        if (Double.isNaN(previousDy)) {
            return false;
        }
        return dy <= previousDy - 0.024;
    }
}

