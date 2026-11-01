package de.kyrosmp.anticheat.checks.movement;

/**
 * Minimal re-implementation of the vanilla 1.21 player travel step
 * (LivingEntity#aiStep/travelInAir) on flat ground with an optional ceiling. Used to prove that the
 * prediction layer never rejects legitimate motion. Float/double conversions follow the vanilla
 * code.
 */
final class VanillaSim {
    double x;
    double y;
    double z;
    double vx;
    double vy;
    double vz;
    boolean onGround = true;
    double groundY;
    double ceilingY = Double.POSITIVE_INFINITY;
    float blockFriction = 0.6f;

    /** movement_speed attribute value as the server reports it (includes sprint). */
    double attr = 0.13;

    boolean sprint = true;
    double gravity = 0.08;
    double jumpPower = 0.42;
    float yaw;
    double forward = 1.0;
    double strafe;
    double inputScale = 1.0;
    boolean jump;

    VanillaSim(double groundY) {
        this.groundY = groundY;
        this.y = groundY;
        this.vy = -0.0784000015258789;
    }

    void tick() {
        if (Math.abs(vx) < 0.003) vx = 0.0;
        if (Math.abs(vy) < 0.003) vy = 0.0;
        if (Math.abs(vz) < 0.003) vz = 0.0;
        if (jump && onGround) {
            vy = jumpPower;
            if (sprint) {
                float r = yaw * ((float) Math.PI / 180f);
                vx += -Math.sin(r) * 0.2f;
                vz += Math.cos(r) * 0.2f;
            }
        }
        float f = onGround ? blockFriction : 1.0f;
        float speed =
                onGround
                        ? (float) attr * (0.21600002f / (f * f * f))
                        : (sprint ? 0.025999999f : 0.02f);
        double fw = forward * 0.98f * inputScale;
        double st = strafe * 0.98f * inputScale;
        double len = fw * fw + st * st;
        if (len > 1.0) {
            double n = Math.sqrt(len);
            fw /= n;
            st /= n;
        }
        double sx = st * speed;
        double sz = fw * speed;
        float sin = (float) Math.sin(yaw * ((float) Math.PI / 180f));
        float cos = (float) Math.cos(yaw * ((float) Math.PI / 180f));
        vx += sx * cos - sz * sin;
        vz += sz * cos + sx * sin;
        // move with vertical collisions against the floor and ceiling
        double ny = y + vy;
        boolean below = false;
        boolean above = false;
        if (ny < groundY) {
            ny = groundY;
            below = vy < 0.0;
        }
        if (ny + 1.8 > ceilingY) {
            ny = ceilingY - 1.8;
            above = true;
        }
        y = ny;
        x += vx;
        z += vz;
        onGround = below;
        if (below || above) {
            vy = 0.0;
        }
        vy = (vy - gravity) * 0.98f;
        double f1 = f * 0.91f;
        vx *= f1;
        vz *= f1;
    }
}
