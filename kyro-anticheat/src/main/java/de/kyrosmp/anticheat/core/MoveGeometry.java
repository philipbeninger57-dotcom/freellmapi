/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.core;

public interface MoveGeometry {
    /** Server-side support directly below the start position of the move (3.7). */
    default boolean fromGrounded() {
        return false;
    }

    /** Exact contact with a surface below, like vanilla's onGround (3.7). */
    default boolean groundStrict() {
        return this.ground();
    }

    /** Real liquids, bubble columns or waterlogged blocks around the player (3.7). */
    default boolean liquidNearby() {
        return this.liquidOrClimbable();
    }

    /** Ladders, vines and scaffolding around the player (3.7). */
    default boolean climbableNearby() {
        return false;
    }

    /** Cobwebs, berry bushes, powder snow and honey: blocks that only slow movement (3.7). */
    default boolean stuckNearby() {
        return false;
    }

    /** Moving pistons, piston heads or shulker boxes that can push a player (3.7). */
    default boolean movingBlockNearby() {
        return false;
    }

    public boolean ground();

    public boolean restingAtPreviousXZ();

    public boolean liquidOrClimbable();

    public boolean bounceSurface();

    public boolean slipperySurface();

    public boolean ceiling();

    public boolean nearGround();

    public boolean safeNoDamageLanding();

    public boolean liquidUnderFeet();

    public boolean bodyWet();

    public boolean collisionNearby();
}

