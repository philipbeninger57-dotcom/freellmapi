package de.kyrosmp.anticheat.core;

public interface MoveGeometry {
    default boolean fromGrounded() {
        return false;
    }

    default boolean groundStrict() {
        return this.ground();
    }

    default boolean liquidNearby() {
        return this.liquidOrClimbable();
    }

    default boolean climbableNearby() {
        return false;
    }

    default boolean stuckNearby() {
        return false;
    }

    default boolean movingBlockNearby() {
        return false;
    }

    boolean ground();

    boolean restingAtPreviousXZ();

    boolean liquidOrClimbable();

    boolean bounceSurface();

    boolean slipperySurface();

    boolean ceiling();

    boolean nearGround();

    boolean safeNoDamageLanding();

    boolean liquidUnderFeet();

    boolean bodyWet();

    boolean collisionNearby();
}
