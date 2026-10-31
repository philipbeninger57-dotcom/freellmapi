package de.kyrosmp.anticheat.core;

import org.bukkit.GameMode;
import org.bukkit.World;

public final class MoveContext {
    public final long now;
    public final long nanoNow;
    public final World world;
    public final double fromX;
    public final double fromY;
    public final double fromZ;
    public final double toX;
    public final double toY;
    public final double toZ;
    public final double dx;
    public final double dy;
    public final double dz;
    public final double horizontal;
    public final double total;
    public final float yaw;
    public final float pitch;
    public final GameMode gameMode;
    public final boolean allowFlight;
    public final boolean flying;
    public final boolean gliding;
    public final boolean riptiding;
    public final boolean swimming;
    public final boolean insideVehicle;
    public final boolean sprinting;
    public final boolean clientOnGround;
    public final boolean invulnerable;
    public final boolean holdingMace;
    public final boolean fallDamageEnabled;
    public final boolean levitation;
    public final boolean slowFalling;
    public final int ping;
    public final int jumpAmplifier;
    public final int speedAmplifier;
    public final float walkSpeed;
    public final double movementSpeedAttr;
    public final double jumpStrengthAttr;
    public final double gravityAttr;
    public final double safeFallAttr;
    public final double fallDamageMultiplierAttr;
    public final double stepHeightAttr;
    public final boolean usingItem;
    public final int itemUseTicks;
    public final int itemUseRemaining;
    public final double itemUseSpeed;
    public final boolean lungeWeapon;
    public final boolean sneaking;

    /** Value of the {@code sneaking_speed} attribute (vanilla 0.3, raised by Swift Sneak). */
    public final double sneakingSpeedAttr;

    /** Hunger bar (0-20). Vanilla clients cannot start sprinting at 6 or below. */
    public final int foodLevel;

    public final MoveGeometry geo;

    private MoveContext(MoveContext.Builder b) {
        this.now = b.now;
        this.nanoNow = b.nanoNow;
        this.world = b.world;
        this.fromX = b.fromX;
        this.fromY = b.fromY;
        this.fromZ = b.fromZ;
        this.toX = b.toX;
        this.toY = b.toY;
        this.toZ = b.toZ;
        this.dx = this.toX - this.fromX;
        this.dy = this.toY - this.fromY;
        this.dz = this.toZ - this.fromZ;
        this.horizontal = Math.sqrt(this.dx * this.dx + this.dz * this.dz);
        this.total = this.horizontal + Math.abs(this.dy);
        this.yaw = b.yaw;
        this.pitch = b.pitch;
        this.gameMode = b.gameMode;
        this.allowFlight = b.allowFlight;
        this.flying = b.flying;
        this.gliding = b.gliding;
        this.riptiding = b.riptiding;
        this.swimming = b.swimming;
        this.insideVehicle = b.insideVehicle;
        this.sprinting = b.sprinting;
        this.clientOnGround = b.clientOnGround;
        this.invulnerable = b.invulnerable;
        this.holdingMace = b.holdingMace;
        this.fallDamageEnabled = b.fallDamageEnabled;
        this.levitation = b.levitation;
        this.slowFalling = b.slowFalling;
        this.ping = b.ping;
        this.jumpAmplifier = b.jumpAmplifier;
        this.speedAmplifier = b.speedAmplifier;
        this.walkSpeed = b.walkSpeed;
        this.movementSpeedAttr = b.movementSpeedAttr;
        this.jumpStrengthAttr = b.jumpStrengthAttr;
        this.gravityAttr = b.gravityAttr;
        this.safeFallAttr = b.safeFallAttr;
        this.fallDamageMultiplierAttr = b.fallDamageMultiplierAttr;
        this.stepHeightAttr = b.stepHeightAttr;
        this.usingItem = b.usingItem;
        this.itemUseTicks = b.itemUseTicks;
        this.itemUseRemaining = b.itemUseRemaining;
        this.itemUseSpeed = b.itemUseSpeed;
        this.lungeWeapon = b.lungeWeapon;
        this.sneaking = b.sneaking;
        this.sneakingSpeedAttr = b.sneakingSpeedAttr;
        this.foodLevel = b.foodLevel;
        this.geo = b.geo;
    }

    public double speed3d() {
        return Math.sqrt(this.dx * this.dx + this.dy * this.dy + this.dz * this.dz);
    }

    public static MoveContext.Builder builder() {
        return new MoveContext.Builder();
    }

    public static final class Builder {
        long now;
        long nanoNow;
        World world;
        double fromX;
        double fromY;
        double fromZ;
        double toX;
        double toY;
        double toZ;
        float yaw;
        float pitch;
        GameMode gameMode = GameMode.SURVIVAL;
        boolean allowFlight;
        boolean flying;
        boolean gliding;
        boolean riptiding;
        boolean swimming;
        boolean insideVehicle;
        boolean sprinting;
        boolean clientOnGround;
        boolean invulnerable;
        boolean holdingMace;
        boolean fallDamageEnabled = true;
        boolean levitation;
        boolean slowFalling;
        int ping;
        int jumpAmplifier = -1;
        int speedAmplifier = -1;
        float walkSpeed = 0.2F;
        double movementSpeedAttr = Double.NaN;
        double jumpStrengthAttr = Double.NaN;
        double gravityAttr = Double.NaN;
        double safeFallAttr = Double.NaN;
        double fallDamageMultiplierAttr = Double.NaN;
        double stepHeightAttr = Double.NaN;
        boolean usingItem;
        int itemUseTicks;
        int itemUseRemaining;
        double itemUseSpeed = 0.2;
        boolean lungeWeapon;
        boolean sneaking;
        double sneakingSpeedAttr = Double.NaN;
        int foodLevel = 20;
        MoveGeometry geo;

        public MoveContext.Builder time(long now, long nanoNow) {
            this.now = now;
            this.nanoNow = nanoNow;
            return this;
        }

        public MoveContext.Builder world(World w) {
            this.world = w;
            return this;
        }

        public MoveContext.Builder from(double x, double y, double z) {
            this.fromX = x;
            this.fromY = y;
            this.fromZ = z;
            return this;
        }

        public MoveContext.Builder to(double x, double y, double z) {
            this.toX = x;
            this.toY = y;
            this.toZ = z;
            return this;
        }

        public MoveContext.Builder rotation(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
            return this;
        }

        public MoveContext.Builder gameMode(GameMode gm) {
            this.gameMode = gm;
            return this;
        }

        public MoveContext.Builder allowFlight(boolean v) {
            this.allowFlight = v;
            return this;
        }

        public MoveContext.Builder flying(boolean v) {
            this.flying = v;
            return this;
        }

        public MoveContext.Builder gliding(boolean v) {
            this.gliding = v;
            return this;
        }

        public MoveContext.Builder riptiding(boolean v) {
            this.riptiding = v;
            return this;
        }

        public MoveContext.Builder swimming(boolean v) {
            this.swimming = v;
            return this;
        }

        public MoveContext.Builder insideVehicle(boolean v) {
            this.insideVehicle = v;
            return this;
        }

        public MoveContext.Builder sprinting(boolean v) {
            this.sprinting = v;
            return this;
        }

        public MoveContext.Builder clientOnGround(boolean v) {
            this.clientOnGround = v;
            return this;
        }

        public MoveContext.Builder invulnerable(boolean v) {
            this.invulnerable = v;
            return this;
        }

        public MoveContext.Builder holdingMace(boolean v) {
            this.holdingMace = v;
            return this;
        }

        public MoveContext.Builder fallDamageEnabled(boolean v) {
            this.fallDamageEnabled = v;
            return this;
        }

        public MoveContext.Builder levitation(boolean v) {
            this.levitation = v;
            return this;
        }

        public MoveContext.Builder slowFalling(boolean v) {
            this.slowFalling = v;
            return this;
        }

        public MoveContext.Builder ping(int v) {
            this.ping = v;
            return this;
        }

        public MoveContext.Builder jumpAmplifier(int v) {
            this.jumpAmplifier = v;
            return this;
        }

        public MoveContext.Builder speedAmplifier(int v) {
            this.speedAmplifier = v;
            return this;
        }

        public MoveContext.Builder walkSpeed(float v) {
            this.walkSpeed = v;
            return this;
        }

        public MoveContext.Builder movementSpeedAttr(double v) {
            this.movementSpeedAttr = v;
            return this;
        }

        public MoveContext.Builder jumpStrengthAttr(double v) {
            this.jumpStrengthAttr = v;
            return this;
        }

        public MoveContext.Builder gravityAttr(double v) {
            this.gravityAttr = v;
            return this;
        }

        public MoveContext.Builder safeFallAttr(double v) {
            this.safeFallAttr = v;
            return this;
        }

        public MoveContext.Builder fallDamageMultiplierAttr(double v) {
            this.fallDamageMultiplierAttr = v;
            return this;
        }

        public MoveContext.Builder stepHeightAttr(double v) {
            this.stepHeightAttr = v;
            return this;
        }

        public MoveContext.Builder itemUse(
                boolean using, int usedTicks, int remainingTicks, double speedMultiplier) {
            this.usingItem = using;
            this.itemUseTicks = usedTicks;
            this.itemUseRemaining = remainingTicks;
            this.itemUseSpeed = speedMultiplier;
            return this;
        }

        public MoveContext.Builder lungeWeapon(boolean v) {
            this.lungeWeapon = v;
            return this;
        }

        public MoveContext.Builder sneaking(boolean v) {
            this.sneaking = v;
            return this;
        }

        public MoveContext.Builder sneakingSpeedAttr(double v) {
            this.sneakingSpeedAttr = v;
            return this;
        }

        public MoveContext.Builder foodLevel(int v) {
            this.foodLevel = v;
            return this;
        }

        public MoveContext.Builder geometry(MoveGeometry g) {
            this.geo = g;
            return this;
        }

        public MoveContext build() {
            if (this.geo == null) {
                throw new IllegalStateException("geometry required");
            } else {
                return new MoveContext(this);
            }
        }
    }
}
