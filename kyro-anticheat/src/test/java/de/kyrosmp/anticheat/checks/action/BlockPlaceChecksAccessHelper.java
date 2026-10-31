package de.kyrosmp.anticheat.checks.action;

/** Test access to package-private geometry hooks of BlockPlaceChecks. */
public final class BlockPlaceChecksAccessHelper {
    private BlockPlaceChecksAccessHelper() {}

    public static void setFace(BlockPlaceChecks checks, double margin) {
        checks.faceMargin = (p, blocks) -> margin;
    }

    public static void setSolid(BlockPlaceChecks checks, boolean solid) {
        checks.solid = b -> solid;
    }
}
