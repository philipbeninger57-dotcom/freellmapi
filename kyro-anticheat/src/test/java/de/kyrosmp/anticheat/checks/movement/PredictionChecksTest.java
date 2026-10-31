package de.kyrosmp.anticheat.checks.movement;

import de.kyrosmp.anticheat.core.CheckConfig;
import de.kyrosmp.anticheat.core.MoveContext;
import de.kyrosmp.anticheat.core.MoveGeometry;
import de.kyrosmp.anticheat.core.PlayerData;
import de.kyrosmp.anticheat.core.PreventionConfig;
import de.kyrosmp.anticheat.core.SetbackService;
import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves with a vanilla physics re-implementation that legitimate motion is never rejected and that
 * the common movement cheats are rejected on (one of) the first impossible ticks.
 */
class PredictionChecksTest {
    final List<String> flags = new ArrayList<>();
    int groundSetbacks;
    final FlagSink sink = new FlagSink() {
        public String flag(Player p, PlayerData d, String check, String reason, String details) { flags.add(check + " " + details); return null; }
        public void action(String id, String action) { }
    };
    final SetbackService engine = new SetbackService() {
        public boolean setback(Player p, PlayerData d, String id) { groundSetbacks++; return true; }
        public boolean setbackTo(Player p, PlayerData d, Location to, String id) { groundSetbacks++; return true; }
        public void registerFlyPunishment(Player p, PlayerData d, long now, String id) { fail("no automatic punishment"); }
    };
    /** Every violation is flagged at once so failing assertions show the exact numbers. */
    static final StrictConfig EAGER = new StrictConfig(true, 0.03, 1.0, true, 1200L, true, 1.0, true, 0.04, 1.0,
            true, 1.0, true, 25, 1.0, true, 1.0, true, 1.0, true, true, true, true, true, true);
    final PredictionChecks checks = new PredictionChecks(sink, engine, CheckConfig.defaults(), EAGER);

    /** Feeds simulated ticks through Paper's 1/16-block event filter into the checks. */
    final class Harness {
        final PlayerData d = new PlayerData(UUID.randomUUID(), "sim");
        final VanillaSim sim;
        long now = 100_000L;
        double lx;
        double ly;
        double lz;
        int events;
        int rejects;
        int jumpAmplifier = -1;
        boolean ice;
        boolean usingItem;
        int itemTicks;
        String exempt;
        boolean climbable;
        boolean liquid;

        Harness(VanillaSim sim) {
            this.sim = sim;
            this.lx = sim.x;
            this.ly = sim.y;
            this.lz = sim.z;
            this.d.lastGround = true;
            this.d.resetPrediction(true);
        }

        boolean serverGround(double y) {
            double above = y - sim.groundY;
            return above >= -1.0E-9 && above < 0.12;
        }

        /** @return true when the event of this tick (if any) was rejected */
        boolean tick() {
            sim.tick();
            now += 50L;
            if (ice) d.lastSlipperySurface = now;
            if (usingItem) itemTicks++;
            double dx = sim.x - lx, dy = sim.y - ly, dz = sim.z - lz;
            if (dx * dx + dy * dy + dz * dz <= 1.0 / 256.0) {
                return false;
            }
            return emit(sim.x, sim.y, sim.z);
        }

        boolean emit(double x, double y, double z) {
            boolean fromGround = serverGround(ly);
            boolean toGround = serverGround(y);
            MoveGeometry geo = geometry(toGround, Math.abs(y - sim.groundY) < 1.0E-9, fromGround, y - sim.groundY < 0.6, climbable, liquid);
            MoveContext m = MoveContext.builder().time(now, now * 1_000_000L).from(lx, ly, lz).to(x, y, z)
                    .sprinting(sim.sprint).movementSpeedAttr(sim.attr).jumpAmplifier(jumpAmplifier)
                    .gravityAttr(sim.gravity).ping(50).itemUse(usingItem, itemTicks, 20, 0.2)
                    .geometry(geo).build();
            boolean rejected = checks.evaluate(null, d, m, d.lastGround, toGround, exempt, false);
            events++;
            if (rejected) {
                rejects++;
                // The server answers with a position packet: back to 'from', zero velocity.
                sim.x = lx; sim.y = ly; sim.z = lz;
                sim.vx = 0; sim.vy = 0; sim.vz = 0;
                sim.onGround = fromGround && Math.abs(ly - sim.groundY) < 1.0E-9;
                return true;
            }
            d.lastGround = toGround;
            lx = x; ly = y; lz = z;
            return false;
        }

        void run(int ticks) {
            for (int i = 0; i < ticks; i++) tick();
        }
    }

    static MoveGeometry geometry(boolean ground, boolean strict, boolean fromGround, boolean nearGround, boolean climbable, boolean liquid) {
        return geometry(ground, strict, fromGround, nearGround, climbable, liquid, false);
    }

    static MoveGeometry geometry(boolean ground, boolean strict, boolean fromGround, boolean nearGround, boolean climbable, boolean liquid,
                                 boolean ceiling) {
        return new MoveGeometry() {
            public boolean groundStrict() { return strict; }
            public boolean fromGrounded() { return fromGround; }
            public boolean liquidNearby() { return liquid; }
            public boolean climbableNearby() { return climbable; }
            public boolean ground() { return ground; }
            public boolean restingAtPreviousXZ() { return false; }
            public boolean liquidOrClimbable() { return climbable || liquid; }
            public boolean bounceSurface() { return false; }
            public boolean slipperySurface() { return false; }
            public boolean ceiling() { return ceiling; }
            public boolean nearGround() { return nearGround; }
            public boolean safeNoDamageLanding() { return false; }
            public boolean liquidUnderFeet() { return false; }
            public boolean bodyWet() { return false; }
            public boolean collisionNearby() { return false; }
        };
    }

    // ------------------------------------------------------------------ legitimate motion

    @Test void standingJumpsInPlaceIncludingMergedApexTicksAreAccepted() {
        Harness h = new Harness(new VanillaSim(64));
        h.sim.forward = 0;
        h.sim.jump = true;
        h.run(400);
        assertEquals(0, h.rejects, flags.toString());
        assertTrue(h.events > 100);
    }

    @Test void continuousSprintJumpingIsAccepted() {
        Harness h = new Harness(new VanillaSim(64));
        h.sim.jump = true;
        h.run(600);
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void sprintingStartingAndStoppingOnTheGroundIsAccepted() {
        Harness h = new Harness(new VanillaSim(64));
        for (int round = 0; round < 10; round++) {
            h.sim.forward = 1; h.run(30);
            h.sim.forward = 0; h.run(15);
        }
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void headHitterSprintJumpingIsAccepted() {
        VanillaSim sim = new VanillaSim(64);
        sim.ceilingY = 66.0;
        Harness h = new Harness(sim);
        sim.jump = true;
        h.run(600);
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void iceHeadHitterSprintJumpingIsAccepted() {
        VanillaSim sim = new VanillaSim(64);
        sim.ceilingY = 66.0;
        sim.blockFriction = 0.989f;
        Harness h = new Harness(sim);
        h.ice = true;
        sim.jump = true;
        h.run(600);
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void jumpBoostAndSpeedTwoAreAccepted() {
        VanillaSim sim = new VanillaSim(64);
        sim.jumpPower = 0.42 + 0.1 * 3;
        sim.attr = 0.1 * 1.3 * 1.4;
        Harness h = new Harness(sim);
        h.jumpAmplifier = 2;
        sim.jump = true;
        h.run(600);
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void walkingOffAHighLedgeAndFallingIsAccepted() {
        VanillaSim sim = new VanillaSim(84);
        Harness h = new Harness(sim);
        h.run(10);
        sim.groundY = 44;        // the floor drops away: a 40 block fall
        h.run(200);
        assertEquals(0, h.rejects, flags.toString());
        assertEquals(44.0, sim.y, 1.0E-9);
    }

    @Test void slowSneakingOffALedgeWithMergedEventsIsAccepted() {
        VanillaSim sim = new VanillaSim(70);
        sim.sprint = false;
        sim.attr = 0.1;
        sim.inputScale = 0.3;
        Harness h = new Harness(sim);
        h.run(20);
        sim.groundY = 60;
        h.run(80);
        sim.jump = true;
        h.run(200);
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void lowGravityAttributeIsFollowed() {
        VanillaSim sim = new VanillaSim(64);
        sim.gravity = 0.04;
        Harness h = new Harness(sim);
        sim.forward = 0.2;
        sim.jump = true;
        h.run(400);
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void knockbackAppliedSeveralTicksAfterTheServerSentItIsAccepted() {
        Harness h = new Harness(new VanillaSim(64));
        h.sim.jump = true;
        for (int hit = 0; hit < 8; hit++) {
            h.run(13);
            h.d.registerPredictionImpulse(0.45, 0.42, 0.1, h.now);
            h.run(3);                                     // ping delay before the client applies it
            h.sim.vx = 0.45; h.sim.vy = 0.42; h.sim.vz = 0.1; // ClientboundSetEntityMotionPacket replaces the motion
            h.run(20);
        }
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void explosionKnockbackAddedToTheCurrentMotionIsAccepted() {
        Harness h = new Harness(new VanillaSim(64));
        h.sim.jump = true;
        h.run(7);
        h.d.registerPredictionImpulse(0.8, 1.1, 0.0, h.now);
        h.run(2);
        h.sim.vx += 0.8; h.sim.vy += 1.1;                  // ClientboundExplodePacket adds the knockback
        h.run(120);
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void itemUseWithTheVanillaSlowdownIsAccepted() {
        Harness h = new Harness(new VanillaSim(64));
        h.run(30);
        h.usingItem = true;
        h.sim.inputScale = 0.2;
        h.run(60);
        h.usingItem = false;
        h.sim.inputScale = 1.0;
        h.run(30);
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void climbingALadderAtVanillaSpeedIsAccepted() {
        Harness h = new Harness(new VanillaSim(64));
        h.exempt = "Liquid/climbable";
        h.climbable = true;
        h.sim.groundY = 0;
        h.sim.onGround = false;
        h.d.lastGround = false;
        for (int i = 0; i < 60; i++) {
            h.sim.vy = 0.2;                                // vanilla sets 0.2 every climbing tick
            h.sim.vx = 0; h.sim.vz = 0; h.sim.forward = 0;
            h.tick();
        }
        assertEquals(0, h.rejects, flags.toString());
    }

    @Test void clientInAnUnloadedChunkAfterATeleportFallsAtMinusPointOne() {
        Harness h = new Harness(new VanillaSim(64));
        h.sim.groundY = 0;
        h.sim.onGround = false;
        h.d.lastGround = false;
        h.d.lastTeleport = h.now;
        for (int i = 0; i < 40; i++) {
            h.now += 50;
            assertFalse(h.emit(h.lx, h.ly - 0.098, h.lz), flags.toString());
        }
    }

    // ------------------------------------------------------------------ cheats

    /** Drives a cheating client: each call is one tick with the given displacement. */
    static boolean cheat(Harness h, double dx, double dy, double dz) {
        h.now += 50;
        return h.emit(h.lx + dx, h.ly + dy, h.lz + dz);
    }

    @Test void hoverAfterTheJumpApexIsRejectedWithinTwoTicksAndSentToGround() {
        Harness h = new Harness(new VanillaSim(64));
        h.d.lastGround = true;
        double[] rise = {0.42, 0.3332, 0.24814, 0.16477, 0.08307};
        for (double v : rise) assertFalse(cheat(h, 0.1, v, 0), flags.toString());
        int rejectedAt = -1;
        for (int i = 0; i < 10 && rejectedAt < 0; i++) {
            if (cheat(h, 0.1, 0.0, 0)) rejectedAt = i;
        }
        assertTrue(rejectedAt >= 0 && rejectedAt <= 1, "rejected at " + rejectedAt);
        for (int i = 0; i < 3; i++) cheat(h, 0.1, 0.0, 0);
        assertTrue(groundSetbacks > 0);
    }

    @Test void flyingUpwardsIsRejectedAtOnce() {
        Harness h = new Harness(new VanillaSim(64));
        h.d.lastGround = true;
        assertFalse(cheat(h, 0.2, 0.42, 0));
        int rejected = 0;
        for (int i = 0; i < 10; i++) if (cheat(h, 0.2, 0.4, 0)) rejected++;
        assertTrue(rejected >= 9, "rejected " + rejected);
    }

    @Test void slowGlidingIsRejected() {
        Harness h = new Harness(new VanillaSim(0));
        h.sim.groundY = 0;
        h.d.lastGround = false;
        h.d.predVy = -0.0784;
        h.ly = 100;
        int rejected = 0;
        for (int i = 0; i < 20; i++) if (cheat(h, 0.25, -0.03, 0)) rejected++;
        assertTrue(rejected >= 18, "rejected " + rejected);
    }

    @Test void airJumpWhileFallingIsRejected() {
        Harness h = new Harness(new VanillaSim(0));
        h.ly = 100;
        h.d.lastGround = false;
        h.d.predVy = -0.5;
        assertTrue(cheat(h, 0.1, 0.42, 0));
        assertTrue(flags.isEmpty() || flags.get(0).startsWith("Fly B"));
    }

    @Test void groundSpeedIsRejectedAndCannotBeBuiltUp() {
        Harness h = new Harness(new VanillaSim(64));
        h.run(40);                                   // legit full sprint first
        int rejected = 0;
        for (int i = 0; i < 40; i++) if (cheat(h, 0.36, 0, 0)) rejected++;
        assertTrue(rejected >= 30, "rejected " + rejected);
    }

    @Test void bhopSpeedIsRejected() {
        Harness h = new Harness(new VanillaSim(64));
        h.sim.jump = true;
        h.run(40);
        int rejected = 0;
        for (int i = 0; i < 40; i++) if (cheat(h, 0.55, i % 10 == 0 ? 0.42 : -0.05, 0)) rejected++;
        assertTrue(rejected >= 25, "rejected " + rejected);
    }

    @Test void stepHackIsRejectedButSlabsAndStairsAreNot() {
        Harness h = new Harness(new VanillaSim(64));
        h.d.lastGround = true;
        h.sim.groundY = 64.5;
        assertFalse(h.emit(h.lx + 0.2, 64.5, h.lz));   // half slab step
        h.sim.groundY = 65.5;
        assertTrue(h.emit(h.lx + 0.2, 65.5, h.lz));    // a full block in one move
        assertEquals(1, h.rejects);
    }

    @Test void noSlowIsRejectedWithinTwoTicks() {
        Harness h = new Harness(new VanillaSim(64));
        h.run(30);
        h.usingItem = true;
        h.itemTicks = 5;
        int firstReject = -1;
        for (int i = 0; i < 6; i++) {
            h.itemTicks++;
            if (cheat(h, 0.28, 0, 0) && firstReject < 0) firstReject = i;
        }
        assertTrue(firstReject >= 0 && firstReject <= 2, "first reject " + firstReject);
    }

    @Test void fastLadderIsRejected() {
        Harness h = new Harness(new VanillaSim(0));
        h.exempt = "Liquid/climbable";
        h.climbable = true;
        h.ly = 70;
        h.d.lastGround = false;
        h.d.predVy = 0.2;
        int rejected = 0;
        for (int i = 0; i < 10; i++) if (cheat(h, 0, 0.45, 0)) rejected++;
        assertTrue(rejected >= 9, "rejected " + rejected);
    }

    @Test void unloadedChunkFallIsNoExcuseWithoutARecentTeleport() {
        Harness h = new Harness(new VanillaSim(0));
        h.ly = 100;
        h.d.lastGround = false;
        h.d.lastTeleport = 0;
        int rejected = 0;
        for (int i = 0; i < 20; i++) if (cheat(h, 0.2, -0.098, 0)) rejected++;
        // every rejection restarts the client at rest, so at most every other tick passes - and the
        // repeated strikes return the player to the ground
        assertTrue(rejected >= 8, "rejected " + rejected);
        assertTrue(groundSetbacks > 0);
    }

    @Test void impulsesAreSingleUse() {
        Harness h = new Harness(new VanillaSim(0));
        h.ly = 100;
        h.d.lastGround = false;
        h.d.predVy = -0.3;
        h.d.registerPredictionImpulse(0.0, 0.5, 0.0, h.now);
        assertFalse(cheat(h, 0.0, 0.5, 0));          // the knockback itself
        assertFalse(cheat(h, 0.0, 0.41, 0));         // follows gravity from 0.5
        assertTrue(cheat(h, 0.0, 0.5, 0));           // a second boost is not covered
    }

    @Test void disabledPreventionStillFlagsButNeverRejects() {
        checks.withPrevention(new PreventionConfig(true, true, true, false, true));
        Harness h = new Harness(new VanillaSim(64));
        h.d.lastGround = true;
        assertFalse(cheat(h, 0.2, 0.42, 0));
        for (int i = 0; i < 10; i++) assertFalse(cheat(h, 0.2, 0.4, 0));
        assertFalse(flags.isEmpty());
        assertEquals(0, groundSetbacks);
    }

    @Test void mergeModelMatchesVanillaApexSequences() {
        // v0 = 0.0830 is the last rising tick of a standard jump; the apex tick is zeroed by vanilla.
        double[] r = PredictionChecks.merged(0.083, 0.08, 0.0);
        assertTrue(r[0] >= 0.0 && r[0] < 0.01);
        // 0.0839 decays to 0.0038 (not zeroed) and then -0.0747: an event spanning both ticks.
        r = PredictionChecks.merged(0.0839, 0.08, -0.0708);
        assertTrue(r[2] >= -0.0747 - 1.0E-3, "carry must never be stricter than the true last velocity");
    }
}
