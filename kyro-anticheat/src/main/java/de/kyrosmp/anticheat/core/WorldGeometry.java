/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Location
 *  org.bukkit.World
 *  org.bukkit.entity.Player
 *  org.bukkit.util.BoundingBox
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.Collisions;
import de.kyrosmp.anticheat.core.MoveGeometry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

public final class WorldGeometry
implements MoveGeometry {
    private static final byte UNKNOWN = 0;
    private static final byte FALSE = 1;
    private static final byte TRUE = 2;
    private final World world;
    private final Player player;
    private final BoundingBox box;
    private final double fromX;
    private final double fromY;
    private final double toY;
    private final double fromZ;
    private byte fromGround;
    private byte strictGround;
    private int special = -1;
    private byte moving;
    private byte ground;
    private byte resting;
    private byte liquid;
    private byte bounce;
    private byte slippery;
    private byte ceiling;
    private byte nearGround;
    private byte safeLanding;
    private byte liquidUnder;
    private byte bodyWet;
    private byte nearby;

    public WorldGeometry(World world, Player player, Location from, Location to) {
        this.world = world;
        this.player = player;
        this.box = Collisions.playerBox(player, to);
        this.fromX = from.getX();
        this.fromY = from.getY();
        this.toY = to.getY();
        this.fromZ = from.getZ();
    }

    @Override
    public boolean groundStrict() {
        if (this.strictGround == 0) {
            this.strictGround = WorldGeometry.of(Collisions.touchingGround(this.world, this.box));
        }
        return this.strictGround == 2;
    }

    @Override
    public boolean fromGrounded() {
        if (this.fromGround == 0) {
            BoundingBox start = Collisions.playerBox(this.player, new Location(this.world, this.fromX, this.fromY, this.fromZ));
            this.fromGround = WorldGeometry.of(Collisions.isGrounded(this.world, start));
        }
        return this.fromGround == 2;
    }

    private int special() {
        if (this.special < 0) {
            this.special = Collisions.classify(this.world, this.box.clone().expand(0.3, 0.3, 0.3));
        }
        return this.special;
    }

    @Override
    public boolean liquidNearby() {
        return (this.special() & Collisions.LIQUID) != 0;
    }

    @Override
    public boolean climbableNearby() {
        return (this.special() & Collisions.CLIMBABLE) != 0;
    }

    @Override
    public boolean stuckNearby() {
        return (this.special() & Collisions.STUCK) != 0;
    }

    @Override
    public boolean movingBlockNearby() {
        if (this.moving == 0) {
            this.moving = WorldGeometry.of(Collisions.movingBlockNearby(this.world, this.box));
        }
        return this.moving == 2;
    }

    public BoundingBox box() {
        return this.box;
    }

    private static byte of(boolean b) {
        return b ? (byte)2 : 1;
    }

    @Override
    public boolean ground() {
        if (this.ground == 0) {
            this.ground = WorldGeometry.of(Collisions.isGrounded(this.world, this.box));
        }
        return this.ground == 2;
    }

    @Override
    public boolean restingAtPreviousXZ() {
        if (this.resting == 0) {
            this.resting = WorldGeometry.of(Collisions.restingAt(this.world, this.player, this.fromX, this.toY, this.fromZ));
        }
        return this.resting == 2;
    }

    @Override
    public boolean liquidOrClimbable() {
        if (this.liquid == 0) {
            this.liquid = WorldGeometry.of(Collisions.touchesLiquidOrClimbable(this.world, this.box));
        }
        return this.liquid == 2;
    }

    @Override
    public boolean bounceSurface() {
        if (this.bounce == 0) {
            this.bounce = WorldGeometry.of(Collisions.onBounceSurface(this.world, this.box));
        }
        return this.bounce == 2;
    }

    @Override
    public boolean slipperySurface() {
        if (this.slippery == 0) {
            this.slippery = WorldGeometry.of(Collisions.onSlipperySurface(this.world, this.box));
        }
        return this.slippery == 2;
    }

    @Override
    public boolean ceiling() {
        if (this.ceiling == 0) {
            this.ceiling = WorldGeometry.of(Collisions.hasCeiling(this.world, this.box));
        }
        return this.ceiling == 2;
    }

    @Override
    public boolean nearGround() {
        if (this.nearGround == 0) {
            this.nearGround = WorldGeometry.of(Collisions.nearGround(this.world, this.box));
        }
        return this.nearGround == 2;
    }

    @Override
    public boolean safeNoDamageLanding() {
        if (this.safeLanding == 0) {
            this.safeLanding = WorldGeometry.of(Collisions.safeNoDamageLanding(this.world, this.box));
        }
        return this.safeLanding == 2;
    }

    @Override
    public boolean liquidUnderFeet() {
        if (this.liquidUnder == 0) {
            BoundingBox under = new BoundingBox(this.box.getMinX() + 0.001, this.box.getMinY() - 0.2, this.box.getMinZ() + 0.001, this.box.getMaxX() - 0.001, this.box.getMinY() - 0.01, this.box.getMaxZ() - 0.001);
            this.liquidUnder = WorldGeometry.of(Collisions.touches(this.world, under, b -> b.isLiquid()));
        }
        return this.liquidUnder == 2;
    }

    @Override
    public boolean bodyWet() {
        if (this.bodyWet == 0) {
            BoundingBox body = new BoundingBox(this.box.getMinX(), this.box.getMinY() + 0.05, this.box.getMinZ(), this.box.getMaxX(), this.box.getMaxY(), this.box.getMaxZ());
            this.bodyWet = WorldGeometry.of(Collisions.touches(this.world, body, b -> b.isLiquid() || Collisions.waterlogged(b)));
        }
        return this.bodyWet == 2;
    }

    @Override
    public boolean collisionNearby() {
        if (this.nearby == 0) {
            boolean hit;
            try {
                hit = Collisions.hasCollision(this.world, this.box.clone().expand(0.25), true);
            }
            catch (Throwable ex) {
                hit = true;
            }
            this.nearby = WorldGeometry.of(hit);
        }
        return this.nearby == 2;
    }
}

